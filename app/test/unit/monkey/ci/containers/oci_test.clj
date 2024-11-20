(ns monkey.ci.containers.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [containers :as mcc]
             [cuid :as cuid]
             [oci :as oci]
             [protocols :as p]
             [runtime :as rt]
             [utils :as u]
             [version :as v]]
            [monkey.ci.common.preds :as cp]
            [monkey.ci.config.sidecar :as cs]
            [monkey.ci.containers.oci :as sut]
            [monkey.ci.events.core :as ec]
            [monkey.ci.spec
             [events :as se]
             [sidecar :as ss]]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.runtime :as trt]))

(defn- find-volume-entry [vol n]
  (->> vol :configs (filter (comp (partial = n) :file-name)) first))

(defn- parse-b64-edn
  "Parses input string as bas64-encoded edn"
  [s]
  (let [b (-> (java.util.Base64/getDecoder)
              (.decode s))]
    (with-open [r (io/reader (java.io.ByteArrayInputStream. b))]
      (u/parse-edn r))))

(defn random-build-sid []
  (repeatedly 3 cuid/random-cuid))

(def default-rt (-> (trt/test-runtime)
                    (assoc :build {:checkout-dir "/tmp"})))

(def default-config
  {:runtime default-rt
   :build (:build default-rt)})

(deftest instance-config
  (testing "creates configuration map"
    (let [ic (sut/instance-config default-config)]
      (is (map? ic))
      (is (string? (:shape ic)))))

  (testing "display name contains build id, pipeline index and job index"
    (is (= "test-build-test-job"
           (->> {:build {:build-id "test-build"
                         :checkout-dir "/tmp"}
                 :job {:id "test-job"}}
                (sut/instance-config)
                :display-name))))

  (testing "has tags from sid"
    (let [tags (->> {:build {:sid ["test-cust" "test-repo"]
                             :checkout-dir "/tmp"}}
                    (sut/instance-config)
                    :freeform-tags)]
      (is (= "test-cust" (get tags "customer-id")))
      (is (= "test-repo" (get tags "repo-id")))))

  (testing "merges in existing tags"
    (let [tags (->> {:build {:sid ["test-cust" "test-repo"]
                             :checkout-dir "/tmp"}
                     :oci {:freeform-tags {"env" "test"}}}
                    (sut/instance-config)
                    :freeform-tags)]
      (is (= "test-cust" (get tags "customer-id")))
      (is (= "test" (get tags "env")))))

  (testing "all containers have checkout volume"
    (letfn [(has-checkout-vol? [c]
              (some (comp (partial = oci/checkout-vol) :volume-name)
                    (:volume-mounts c)))]
      (is (every? has-checkout-vol? (:containers (sut/instance-config default-config))))))

  (testing "pod memory"
    (testing "has default value"
      (is (number? (-> (sut/instance-config default-config)
                       :shape-config
                       :memory-in-g-bs))))

    (testing "can specify custom memory limit"
      (is (= 4 (-> (sut/instance-config (-> default-config
                                            (assoc-in [:job :memory] 4)))
                   :shape-config
                   :memory-in-g-bs))))

    (testing "limited to max memory"
      (is (= sut/max-pod-memory
             (-> (sut/instance-config (-> default-config
                                          (assoc-in [:job :memory] 10000)))
                 :shape-config
                 :memory-in-g-bs)))))

  (testing "pod architecture"
    (testing "has default value"
      (is (= "CI.Standard.A1.Flex"
             (-> (sut/instance-config default-config)
                 :shape))))

    (testing "can choose ARM"
      (is (= "CI.Standard.A1.Flex"
             (-> (sut/instance-config (assoc-in default-config
                                                [:job :arch] :arm))
                 :shape))))

    (testing "can choose AMD"
      (is (= "CI.Standard.E4.Flex"
             (-> (sut/instance-config (assoc-in default-config
                                                [:job :arch] :amd))
                 :shape)))))

  (testing "pod cpus"
    (testing "has default value"
      (is (number? (-> (sut/instance-config default-config)
                       :shape-config
                       :ocpus))))

    (testing "can specify custom cpus limit"
      (is (= 4 (-> (sut/instance-config (-> default-config
                                            (assoc-in [:job :cpus] 4)))
                   :shape-config
                   :ocpus))))

    (testing "limited to max cpus"
      (is (= sut/max-pod-cpus
             (-> (sut/instance-config (-> default-config
                                          (assoc-in [:job :cpus] 10000)))
                 :shape-config
                 :ocpus)))))
  
  (testing "job container"
    (let [find-job-container (fn [ic]
                               (->> ic
                                    :containers
                                    (mc/find-first (cp/prop-pred :display-name "job"))))
          jc (->> {:job {:script ["first" "second"]
                         :container/env {"TEST_ENV" "test-val"}
                         :work-dir "sub"}
                   :build {:checkout-dir "/tmp/test-build"}}
                  (sut/instance-config)
                  (find-job-container))]
      
      (testing "uses shell mounted script"
        (is (= ["/bin/sh" (str sut/script-dir "/job.sh")] (:command jc)))
        (is (= ["0" "1"] (:arguments jc))))

      (testing "includes a script volume"
        (let [v (oci/find-mount jc "scripts")]
          (is (some? v))
          (is (= sut/script-dir (:mount-path v)))))

      (testing "uses shell from job config"
        (let [jc (->> {:job {:script ["test-script"]
                             :shell "/bin/bash"}
                       :build {:checkout-dir "/tmp"}}
                      (sut/instance-config)
                      (find-job-container))]
          (is (= "/bin/bash" (-> jc :command first)))))

      (testing "ignores command and arguments when script specified"
        (let [jc (->> {:job {:script ["test-script"]
                             :container/cmd ["test-command"]
                             :container/args ["test-args"]
                             :shell "/bin/bash"}
                       :build {:checkout-dir "/tmp"}}
                      (sut/instance-config)
                      (find-job-container))]
          (is (not= ["test-command"] (:command jc)))
          (is (not= ["test-args"] (:arguments jc)))))

      (testing "without script"
        (let [jc (->> {:job {:container/cmd ["test-command"]
                             :container/args ["test" "args"]}
                       :build {:checkout-dir "/tmp"}}
                      (sut/instance-config)
                      (find-job-container))]
          (testing "uses command from job"
            (is (= ["test-command"] (:command jc))))
          
          (testing "uses arguments from job"
            (is (= ["test" "args"] (:arguments jc))))
          
          (testing "does not include script volume"
            (is (nil? (oci/find-mount jc "scripts"))))))
      
      (testing "environment"
        (let [env (:environment-variables jc)]
          (testing "sets work dir to job work dir"
            (is (= "/opt/monkeyci/checkout/work/test-build/sub" (get env "MONKEYCI_WORK_DIR"))))

          (testing "handles relative work dir"
            (let [env (->> {:job {:script ["first" "second"]
                                  :container/env {"TEST_ENV" "test-val"}
                                  :work-dir "sub"}
                            :build {:checkout-dir "/tmp/test-build"}}
                           (sut/instance-config)
                           :containers
                           (mc/find-first (cp/prop-pred :display-name "job"))
                           :environment-variables)]
              (is (= "/opt/monkeyci/checkout/work/test-build/sub" (get env "MONKEYCI_WORK_DIR")))))

          (testing "sets log dir"
            (is (string? (get env "MONKEYCI_LOG_DIR"))))

          (testing "sets script dir"
            (is (string? (get env "MONKEYCI_SCRIPT_DIR"))))

          (testing "adds env specified on the job"
            (is (= "test-val" (get env "TEST_ENV"))))))

      (testing "sets working dir to job work dir"
        (is (= "/opt/monkeyci/checkout/work/test-build/sub"
               (:working-directory jc))))))

  (testing "sidecar container"
    (let [pk (h/generate-private-key)
          ic (->> {:job {:id "test-job"
                         :script ["first" "second"]
                         :save-artifacts [{:id "test-artifact"
                                           :path "somewhere"}]
                         :work-dir "sub"}
                   :build {:build-id "test-build"
                           :checkout-dir "/tmp/test-checkout"
                           :workspace "test-build-ws"}
                   :events {:type :manifold}
                   :api {:url "http://test-api"
                         :token "test-token"}
                   :oci {:credentials {:private-key pk}}}
                  (sut/instance-config))
          sc (->> ic
                  :containers
                  (mc/find-first (cp/prop-pred :display-name "sidecar")))]

      (testing "passes config file as arg"
        (is (= "/home/monkeyci/config/config.edn" (second (:arguments sc)))))
      
      (testing "starts sidecar"
        (is (= "sidecar" (nth (:arguments sc) 2))))

      (testing "passes events-file as arg"
        (is (h/contains-subseq? (:arguments sc)
                                ["--events-file" sut/event-file])))

      (testing "passes start-file as arg"
        (is (h/contains-subseq? (:arguments sc)
                                ["--start-file" sut/start-file])))

      (testing "passes abort-file as arg"
        (is (h/contains-subseq? (:arguments sc)
                                ["--abort-file" sut/abort-file])))

      (testing "config volume"
        (let [mnt (oci/find-mount sc "config")
              v (oci/find-volume ic "config")]
          
          (testing "included in config"
            (is (some? mnt))
            (is (some? v))
            (is (= sut/config-dir (:mount-path mnt))))

          (testing "config file"
            (let [e (find-volume-entry v "config.edn")
                  data (some-> e :data (parse-b64-edn))]
              (testing "included in config volume"
                (is (some? e)))

              (testing "matches sidecar config spec"
                (is (spec/valid? ::ss/config data)
                    (spec/explain-str ::ss/config data)))

              (testing "contains job details"
                (is (some? (cs/job data))))

              (testing "contains build details"
                (is (some? (cs/build data))))

              (testing "build checkout dir parent is container work dir"
                (is (= sut/work-dir (-> (cs/build data)
                                        :checkout-dir
                                        (fs/parent)
                                        str))))

              (testing "recalculates job work dir"
                (is (= "/opt/monkeyci/checkout/work/test-checkout/sub"
                       (-> data
                           (cs/job)
                           :work-dir))))))))

      (testing "runs as root to access mount volumes"
        (is (= 0 (-> sc :security-context :run-as-user)))))

    (testing "adds logback config file to config mount"
      (let [vol (-> {:job {:script ["test"]}
                     :sidecar {:log-config "test log config"}
                     :build {:checkout-dir "/tmp"}}
                    (as-> c (sut/instance-config c))
                    (oci/find-volume "config"))]
        (is (some? vol))
        (is (not-empty(:configs vol)))
        (is (some? (find-volume-entry vol "logback.xml"))))))

  (testing "script volume"
    (let [v (->> (assoc default-config :job {:script ["first" "second"]})
                 (sut/instance-config)
                 :volumes
                 (mc/find-first (cp/prop-pred :name "scripts"))
                 :configs)
          by-name (->> v
                       (group-by :file-name)
                       (mc/map-vals first))]
      (testing "not empty"
        (is (not-empty v))
        (is (= 3 (count v))))
      
      (testing "includes job script"
        (is (some? (get by-name "job.sh"))))

      (testing "includes script file per line"
        (is (= (u/->base64 "first")
               (get-in by-name ["0" :data])))
        (is (= (u/->base64 "second")
               (get-in by-name ["1" :data]))))))

  (testing "promtail container"
    (let [ci (->> {:job
                   {:script ["first" "second"]
                    :container/env {"TEST_ENV" "test-val"}
                    :work-dir "sub"}
                   :build
                   {:checkout-dir "/tmp/test-build"}
                   :promtail
                   {:loki-url "http://loki"}}
                  (sut/instance-config))
          pc (->> ci
                  :containers
                  (mc/find-first (cp/prop-pred :display-name "promtail")))]
      (testing "added to instance"
        (is (some? pc)))

      (testing "refers to mounted config file"
        (is (= ["-config.file" "/etc/promtail/config.yml"]
               (:arguments pc)))
        (is (some? (mc/find-first (cp/prop-pred :volume-name "promtail-config")
                                  (:volume-mounts pc)))))

      (testing "monitors script log dir"
        (let [v (oci/find-volume ci "promtail-config")
              contents (some->> v
                                :configs
                                first
                                :data
                                h/base64->
                                yaml/parse-string)]
          (is (some? v))
          (is (map? contents))
          (is (= (str sut/log-dir "/*.log")
                 (-> contents
                     :scrape_configs
                     first
                     :static_configs
                     first
                     :labels
                     :__path__))))))

    (testing "not added if no loki url configured"
      (is (nil? (->> {:job
                      {:script ["first" "second"]
                       :container/env {"TEST_ENV" "test-val"}
                       :work-dir "sub"}
                      :build
                      {:checkout-dir "/tmp/test-build"}}
                     (sut/instance-config)
                     :containers
                     (mc/find-first (cp/prop-pred :display-name "promtail"))))))))

(deftest wait-for-instance-end-events
  (testing "returns a deferred that holds the container and job end events"
    (let [events (ec/make-events {:type :manifold})
          sid (random-build-sid)
          d (sut/wait-for-instance-end-events events sid "this-job" 1000)]
      (is (md/deferred? d))
      (is (not (md/realized? d)) "should not be realized initially")
      (is (some? (ec/post-events events [{:type :container/start
                                          :sid sid}])))
      (is (not (md/realized? d)) "should not be realized after start event")
      (is (some? (ec/post-events events [{:type :container/end
                                          :sid sid
                                          :job-id "this-job"}])))
      (is (not (md/realized? d)) "should not be realized after one event")
      (is (some? (ec/post-events events [{:type :sidecar/end
                                          :sid sid
                                          :job-id "other-job"}])))
      (is (not (md/realized? d)) "should not be realized after other job event")
      (is (some? (ec/post-events events [{:type :sidecar/end
                                          :sid sid
                                          :job-id "this-job"}])))
      (is (= [:container/end :sidecar/end]
             (->> (deref d 100 :timeout)
                  (map :type)))))))

(deftest wait-for-results
  (testing "waits for sidecar and container end events, then fetches details"
    (let [events (ec/make-events {:type :manifold})
          sid (random-build-sid)
          job (h/gen-job)
          conf {:events events
                :job job
                :build {:sid sid}}
          details {:body
                   {:containers [{:display-name sut/sidecar-container-name}
                                 {:display-name sut/job-container-name}]}}
          res (sut/wait-for-results conf 1000 (constantly details))]
      (is (some? (rt/post-events conf [{:type :container/start
                                        :sid sid
                                        :job-id (:id job)}
                                       {:type :sidecar/end
                                        :sid sid
                                        :job-id (:id job)}
                                       {:type :container/end
                                        :sid sid
                                        :job-id (:id job)}])))
      (is (sequential? (get-in @res [:body :containers])))))

  (testing "adds exit codes from events"
    (let [events (ec/make-events {:type :manifold})
          sid (random-build-sid)
          {job-id :id :as job} (h/gen-job)
          conf {:events events
                :job job
                :build {:sid sid}}
          details {:body
                   {:containers [{:display-name sut/sidecar-container-name}
                                 {:display-name sut/job-container-name}]}}
          res (sut/wait-for-results conf 1000 (constantly details))]
      (is (some? (rt/post-events conf
                                 [{:type :container/start
                                   :sid sid
                                   :job-id job-id}
                                  {:type :sidecar/end
                                   :sid sid
                                   :job-id job-id
                                   :result {:exit 1}}
                                  {:type :container/end
                                   :sid sid
                                   :job-id job-id
                                   :result {:exit 2}}])))
      (is (= [1 2] (->> @res :body :containers (map ec/result-exit))))))

  (testing "marks timeout as failure"
    (let [events (ec/make-events {:type :manifold})
          sid (random-build-sid)
          {job-id :id :as job} (h/gen-job)
          conf {:events events
                :job job
                :build {:sid sid}}
          details {:body
                   {:containers [{:display-name sut/sidecar-container-name}
                                 {:display-name sut/job-container-name}]}}
          res (sut/wait-for-results conf 100 (constantly details))]
      (is (some? (rt/post-events conf
                                 [{:type :container/start
                                   :sid sid
                                   :job-id job-id}
                                  {:type :sidecar/end
                                   :sid sid
                                   :job-id job-id
                                   :result {:exit 0}}])))
      (is (= 1 (->> @res :body :containers second ec/result-exit)))))

  (testing "dispatches `job/start` event on container start"
    (let [events (ec/make-events {:type :manifold})
          sid (random-build-sid)
          {job-id :id :as job} (h/gen-job)
          conf {:events events
                :job job
                :build {:sid sid}}
          details {:body
                   {:containers [{:display-name sut/sidecar-container-name}
                                 {:display-name sut/job-container-name}]}}
          res (sut/wait-for-results conf 100 (constantly details))
          evt (ec/wait-for-event events {:types #{:job/start}})]
      (is (some? (rt/post-events conf
                                 [{:type :container/start
                                   :sid sid
                                   :job-id job-id}
                                  {:type :sidecar/end
                                   :sid sid
                                   :job-id job-id
                                   :result {:exit 0}}])))
      (is (some? res))
      (let [evt (deref evt 1000 :timeout)]
        (is (not= :timeout evt))
        (is (= :job/start (:type evt))))))

  (testing "fails when `sidecar/end` is received before `container/start`"
    (let [events (ec/make-events {:type :manifold})
          sid (random-build-sid)
          {job-id :id :as job} (h/gen-job)
          conf {:events events
                :job job
                :build {:sid sid}}
          details {:body
                   {:containers [{:display-name sut/sidecar-container-name}
                                 {:display-name sut/job-container-name}]}}
          res (sut/wait-for-results conf 100 (constantly details))
          evt (ec/wait-for-event events {:types #{:job/start :job/executed}})]
      (is (some? (rt/post-events conf
                                 [{:type :sidecar/end
                                   :sid sid
                                   :job-id job-id
                                   :result {:exit 1}}])))
      (is (some? res))
      (let [evt (deref evt 1000 :timeout)]
        (is (not= :timeout evt))
        (is (= :job/executed (:type evt)))))))

(deftest run-container
  (let [events (h/fake-events)
        sid (random-build-sid)
        runner (sut/->OciContainerRunner {:build {:checkout-dir "/tmp"
                                                  :sid sid}}
                                         events
                                         (constantly 0))
        job {:id "test-job"}]
    
    (testing "can run using type `oci`, returns zero exit code on success"
      (with-redefs [oci/run-instance (constantly (md/success-deferred
                                                  {:status 200
                                                   :body {:containers [{:display-name sut/job-container-name
                                                                        :result {:exit 0}}]}}))]
        (is (= 0 (-> (p/run-container runner job)
                     (deref)
                     :exit)))))

    (testing "returns first nonzero exit code"
      (with-redefs [oci/run-instance (constantly (md/success-deferred
                                                  {:status 200
                                                   :body {:containers [{:result {:exit 0}}
                                                                       {:result {:exit 123}}]}}))]
        (is (= 123 (-> (p/run-container runner job)
                       (deref)
                       :exit)))))

    (testing "fails when `nil` env vars are specified"
      (h/reset-events events)
      (is (nil? @(p/run-container runner (assoc job :container/env {"INVALID_ENV" nil}))))
      (let [evt (->> (h/received-events events)
                     (h/first-event-by-type :job/executed))]
        (is (some? evt))
        (is (= :error (:status evt)))
        (is (re-matches #"Invalid job configuration.*" (get-in evt [:result :message])))))

    (testing "events"
      (h/reset-events events)
      (with-redefs [oci/run-instance (constantly (md/success-deferred
                                                  {:status 200
                                                   :body {:containers [{:display-name sut/job-container-name
                                                                        :result {:exit 0}}]}}))]
        (is (= 0 (-> (p/run-container runner job)
                     (deref)
                     :exit)))
        
        (testing "fires `job/initializing` event"
          (let [evt (->> (h/received-events events)
                         (h/first-event-by-type :job/initializing))]
            (is (some? evt))
            (is (spec/valid? ::se/event evt))
            (is (= sid (:sid evt)))
            (is (number? (:credit-multiplier evt)))))

        (testing "fires `job/executed` event"
          (let [evt (->> (h/received-events events)
                         (h/first-event-by-type :job/executed))]
            (is (some? evt))
            (is (spec/valid? ::se/event evt)
                (spec/explain-str ::se/event evt))
            (is (= sid (:sid evt))))))

      (testing "fires event in case of oci error"
        (h/reset-events events)
        (with-redefs [oci/run-instance (constantly (md/success-deferred
                                                    {:status 500
                                                     :exception (ex-info "oci error" {})}))]
          (is (nil? @(p/run-container runner job)))
          (let [evt (->> (h/received-events events)
                         (h/first-event-by-type :job/executed))]
            (is (some? evt))
            (is (= "oci error" (get-in evt [:result :message]))))))
      
      (testing "fires event in case of async exception"
        (h/reset-events events)
        (with-redefs [oci/run-instance (constantly (md/error-deferred
                                                    (ex-info "infra error" {})))]
          (is (nil? @(p/run-container runner job)))
          (let [evt (->> (h/received-events events)
                         (h/first-event-by-type :job/executed))]
            (is (some? evt))
            (is (= "infra error" (get-in evt [:result :message])))))))))

