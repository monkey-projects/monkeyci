(ns monkey.ci.containers.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [config :as c]
             [containers :as mcc]
             [oci :as oci]
             [runtime :as rt]
             [utils :as u]]
            [monkey.ci.containers.oci :as sut]
            [monkey.ci.events.core :as ec]
            [monkey.ci.helpers :as h]))

(defn- find-volume-entry [vol n]
  (->> vol :configs (filter (comp (partial = n) :file-name)) first))

(defn- parse-b64-edn
  "Parses input string as bas64-encoded edn"
  [s]
  (let [b (-> (java.util.Base64/getDecoder)
              (.decode s))]
    (with-open [r (io/reader (java.io.ByteArrayInputStream. b))]
      (u/parse-edn r))))

(def default-rt {:build {:checkout-dir "/tmp"}})

(deftest instance-config
  (testing "creates configuration map"
    (let [ic (sut/instance-config {} default-rt)]
      (is (map? ic))
      (is (string? (:shape ic)))))

  (testing "display name contains build id, pipeline index and job index"
    (is (= "test-build-test-job"
           (->> {:build {:build-id "test-build"
                         :checkout-dir "/tmp"}
                 :job {:id "test-job"}}
                (sut/instance-config {})
                :display-name))))

  (testing "has tags from sid"
    (let [tags (->> {:build {:sid ["test-cust" "test-repo"]
                             :checkout-dir "/tmp"}}
                    (sut/instance-config {})
                    :freeform-tags)]
      (is (= "test-cust" (get tags "customer-id")))
      (is (= "test-repo" (get tags "repo-id")))))

  (testing "merges in existing tags"
    (let [tags (->> {:build {:sid ["test-cust" "test-repo"]
                             :checkout-dir "/tmp"}}
                    (sut/instance-config {:freeform-tags {"env" "test"}})
                    :freeform-tags)]
      (is (= "test-cust" (get tags "customer-id")))
      (is (= "test" (get tags "env")))))

  (testing "all containers have checkout volume"
    (letfn [(has-checkout-vol? [c]
              (some (comp (partial = oci/checkout-vol) :volume-name)
                    (:volume-mounts c)))]
      (is (every? has-checkout-vol? (:containers (sut/instance-config {} default-rt))))))

  (testing "pod memory"
    (testing "has default value"
      (is (number? (-> (sut/instance-config {} default-rt)
                       :shape-config
                       :memory-in-g-bs))))

    (testing "can specify custom memory limit"
      (is (= 4 (-> (sut/instance-config {} (-> default-rt
                                               (assoc-in [:job :memory] 4)))
                   :shape-config
                   :memory-in-g-bs))))

    (testing "limited to max memory"
      (is (= sut/max-pod-memory
             (-> (sut/instance-config {} (-> default-rt
                                             (assoc-in [:job :memory] 10000)))
                 :shape-config
                 :memory-in-g-bs)))))

  (testing "pod architecture"
    (testing "has default value"
      (is (= "CI.Standard.A1.Flex"
             (-> (sut/instance-config {} default-rt)
                 :shape))))

    (testing "can choose ARM"
      (is (= "CI.Standard.A1.Flex"
             (-> (sut/instance-config {} (assoc-in default-rt
                                                   [:job :arch] :arm))
                 :shape))))

    (testing "can choose AMD"
      (is (= "CI.Standard.E4.Flex"
             (-> (sut/instance-config {} (assoc-in default-rt
                                                   [:job :arch] :amd))
                 :shape)))))

  (testing "pod cpus"
    (testing "has default value"
      (is (number? (-> (sut/instance-config {} default-rt)
                       :shape-config
                       :ocpus))))

    (testing "can specify custom cpus limit"
      (is (= 4 (-> (sut/instance-config {} (-> default-rt
                                               (assoc-in [:job :cpus] 4)))
                   :shape-config
                   :ocpus))))

    (testing "limited to max cpus"
      (is (= sut/max-pod-cpus
             (-> (sut/instance-config {} (-> default-rt
                                             (assoc-in [:job :cpus] 10000)))
                 :shape-config
                 :ocpus)))))
  
  (testing "job container"
    (let [jc (->> {:job {:script ["first" "second"]
                         :container/env {"TEST_ENV" "test-val"}
                         :work-dir "/tmp/test-build/sub"}
                   :build {:checkout-dir "/tmp/test-build"}}
                  (sut/instance-config {})
                  :containers
                  (mc/find-first (u/prop-pred :display-name "job")))]
      
      (testing "uses shell mounted script"
        (is (= ["/bin/sh" (str sut/script-dir "/job.sh")] (:command jc)))
        (is (= ["0" "1"] (:arguments jc))))
      
      (testing "includes a script volume"
        (let [v (oci/find-mount jc "scripts")]
          (is (some? v))
          (is (= sut/script-dir (:mount-path v)))))

      (testing "environment"
        (let [env (:environment-variables jc)]
          (testing "sets work dir to job work dir"
            (is (= "/opt/monkeyci/checkout/work/test-build/sub" (get env "MONKEYCI_WORK_DIR"))))

          (testing "handles relative work dir"
            (let [env (->> {:job {:script ["first" "second"]
                                  :container/env {"TEST_ENV" "test-val"}
                                  :work-dir "sub"}
                            :build {:checkout-dir "/tmp/test-build"}}
                           (sut/instance-config {})
                           :containers
                           (mc/find-first (u/prop-pred :display-name "job"))
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
          conf {:oci {:credentials {:private-key pk}}
                :events {:type :zmq
                         :client {:address "inproc://test"}
                         :server {:enabled true}}
                :args "test-args"}
          ic (->> {:job {:script ["first" "second"]
                         :save-artifacts [{:id "test-artifact"
                                           :path "somewhere"}]
                         :work-dir "/tmp/test-checkout/sub"}
                   :build {:build-id "test-build"
                           :checkout-dir "/tmp/test-checkout"}
                   :config conf}
                  (sut/instance-config {:credentials {:private-key pk}}))
          sc (->> ic
                  :containers
                  (mc/find-first (u/prop-pred :display-name "sidecar")))]

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

      (testing "passes job config as arg"
        (is (h/contains-subseq? (:arguments sc)
                                ["--job-config" "/home/monkeyci/config/job.edn"])))

      (testing "config volume"
        (let [mnt (oci/find-mount sc "config")
              v (oci/find-volume ic "config")]
          
          (testing "included in config"
            (is (some? mnt))
            (is (some? v))
            (is (= sut/config-dir (:mount-path mnt))))

          (testing "job details"
            (let [e (find-volume-entry v "job.edn")
                  data (some-> e :data (parse-b64-edn))]
              (testing "included in config volume"
                (is (some? e)))

              (testing "contains job details"
                (is (contains? data :job)))

              (testing "recalculates job work dir"
                (is (= "/opt/monkeyci/checkout/work/test-checkout/sub"
                       (-> data
                           :job
                           :work-dir))))))

          (testing "config file"
            (let [e (find-volume-entry v "config.edn")
                  data (some-> e :data (parse-b64-edn))]
              (testing "included in config volume"
                (is (some? e)))

              (testing "contains build details"
                (is (contains? data :build)))))))

      (testing "runs as root to access mount volumes"
        (is (= 0 (-> sc :security-context :run-as-user)))))

    (testing "adds logback config file to config mount"
      (let [vol (-> {:job {:script ["test"]}
                     :config {:sidecar {:log-config "test log config"}}
                     :build {:checkout-dir "/tmp"}}
                    (as-> c (sut/instance-config {} c))
                    (oci/find-volume "config"))]
        (is (some? vol))
        (is (not-empty(:configs vol)))
        (is (some? (find-volume-entry vol "logback.xml"))))))

  (testing "script volume"
    (let [v (->> (assoc default-rt :job {:script ["first" "second"]})
                 (sut/instance-config {})
                 :volumes
                 (mc/find-first (u/prop-pred :name "scripts"))
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
                    :work-dir "/tmp/test-build/sub"}
                   :build
                   {:checkout-dir "/tmp/test-build"}
                   :config {:promtail
                            {:loki-url "http://loki"}}}
                  (sut/instance-config {}))
          pc (->> ci
                  :containers
                  (mc/find-first (u/prop-pred :display-name "promtail")))]
      (testing "added to instance"
        (is (some? pc)))

      (testing "refers to mounted config file"
        (is (= ["-config.file" "/etc/promtail/config.yml"]
               (:arguments pc)))
        (is (some? (mc/find-first (u/prop-pred :volume-name "promtail-config")
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
                       :work-dir "/tmp/test-build/sub"}
                      :build
                      {:checkout-dir "/tmp/test-build"}}
                     (sut/instance-config {})
                     :containers
                     (mc/find-first (u/prop-pred :display-name "promtail"))))))))

(deftest wait-for-instance-end-events
  (testing "returns a deferred that holds the container and job end events"
    (let [events (ec/make-events {:events {:type :manifold}})
          sid (repeatedly 3 random-uuid)
          d (sut/wait-for-instance-end-events events sid "this-job" 1000)]
      (is (md/deferred? d))
      (is (not (md/realized? d)) "should not be realized initially")
      (is (some? (ec/post-events events [{:type :container/start
                                          :sid sid}])))
      (is (not (md/realized? d)) "should not be realized after start event")
      (is (some? (ec/post-events events [{:type :container/end
                                          :sid sid
                                          :job {:id "this-job"}}])))
      (is (not (md/realized? d)) "should not be realized after one event")
      (is (some? (ec/post-events events [{:type :sidecar/end
                                          :sid sid
                                          :job {:id "other-job"}}])))
      (is (not (md/realized? d)) "should not be realized after other job event")
      (is (some? (ec/post-events events [{:type :sidecar/end
                                          :sid sid
                                          :job {:id "this-job"}}])))
      (is (= [:container/end :sidecar/end]
             (->> (deref d 100 :timeout)
                  (map :type)))))))

(deftest wait-or-timeout
  (testing "waits for sidecar and container end events, then fetches details"
    (let [events (ec/make-events {:events {:type :manifold}})
          sid (repeatedly 3 random-uuid)
          job-id "test-job"
          rt {:events events
              :job {:id job-id}}
          details {:body
                   {:containers [{:display-name sut/sidecar-container-name}
                                 {:display-name sut/job-container-name}]}}
          res (sut/wait-or-timeout rt 1000 (constantly details))]
      (is (some? (rt/post-events rt [{:type :sidecar/end
                                      :sid sid
                                      :job {:id job-id}}
                                     {:type :container/end
                                      :sid sid
                                      :job {:id job-id}}])))
      (is (sequential? (get-in @res [:body :containers])))))

  (testing "adds exit codes from events"
    (let [events (ec/make-events {:events {:type :manifold}})
          sid (repeatedly 3 random-uuid)
          job-id "test-job"
          rt {:events events
              :job {:id job-id}}
          details {:body
                   {:containers [{:display-name sut/sidecar-container-name}
                                 {:display-name sut/job-container-name}]}}
          res (sut/wait-or-timeout rt 1000 (constantly details))]
      (is (some? (rt/post-events rt [{:type :sidecar/end
                                      :sid sid
                                      :job {:id job-id}
                                      :result {:exit 1}}
                                     {:type :container/end
                                      :sid sid
                                      :job {:id job-id}
                                      :result {:exit 2}}])))
      (is (= [1 2] (->> @res :body :containers (map ec/result-exit))))))

  (testing "marks timeout as failure"
    (let [events (ec/make-events {:events {:type :manifold}})
          sid (repeatedly 3 random-uuid)
          job-id "test-job"
          rt {:events events
              :job {:id job-id}}
          details {:body
                   {:containers [{:display-name sut/sidecar-container-name}
                                 {:display-name sut/job-container-name}]}}
          res (sut/wait-or-timeout rt 100 (constantly details))]
      (is (some? (rt/post-events rt [{:type :sidecar/end
                                      :sid sid
                                      :job {:id job-id}
                                      :result {:exit 0}}])))
      (is (= 1 (->> @res :body :containers second ec/result-exit))))))

(deftest run-container
  (testing "can run using type `oci`, returns zero exit code on success"
    (with-redefs [oci/run-instance (constantly (md/success-deferred
                                                {:status 200
                                                 :body {:containers [{:display-name sut/job-container-name
                                                                      :result {:exit 0}}]}}))]
      (is (= 0 (-> (mcc/run-container {:containers {:type :oci}
                                       :build {:checkout-dir "/tmp"}})
                   (deref)
                   :exit)))))

  (testing "returns first nonzero exit code"
    (with-redefs [oci/run-instance (constantly (md/success-deferred
                                                {:status 200
                                                 :body {:containers [{:result {:exit 0}}
                                                                     {:result {:exit 123}}]}}))]
      (is (= 123 (-> (mcc/run-container {:containers {:type :oci}
                                         :build {:checkout-dir "/tmp"}})
                     (deref)
                     :exit))))))

(deftest normalize-key
  (testing "merges with oci"
    (is (= {:type :oci
            :key "value"}
           (-> (c/normalize-key :containers
                                {:containers {:type :oci}
                                 :oci {:key "value"}})
               :containers
               (select-keys [:type :key])))))

  (testing "takes configured tag"
    (is (= "test-version"
           (->> {:containers {:type :oci
                              :image-tag "test-version"}}
                (c/normalize-key :containers)
                :containers
                :image-tag))))

  (testing "takes app version if no tag specified"
    (is (= (c/version)
           (->> {:containers {:type :oci}}
                (c/normalize-key :containers)
                :containers
                :image-tag))))

  (testing "formats using app version when format string specified"
    (is (= (str "test-format-" (c/version))
           (->> {:containers {:type :oci
                              :image-tag "test-format-%s"}}
                (c/normalize-key :containers)
                :containers
                :image-tag)))))
