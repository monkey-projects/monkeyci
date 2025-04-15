(ns monkey.ci.containers.oci-test
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [clojure.string :as cstr]
            [clojure.test :refer [deftest is testing]]
            [medley.core :as mc]
            [monkey.ci
             [cuid :as cuid]
             [oci :as oci]
             [utils :as u]]
            [monkey.ci.common.preds :as cp]
            [monkey.ci.containers
             [common :as cc]
             [oci :as sut]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.sidecar.config :as cs]
            [monkey.ci.spec.sidecar :as ss]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]))

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

  (let [ic (-> default-config
               (assoc :oci {:shape "test-shape"
                            :shape-config {:memory-in-g-bs 10}})
               (sut/instance-config))]
    (testing "applies shape from config"
      (is (= "test-shape" (:shape ic)))))

  (testing "display name contains build id, pipeline index and job index"
    (is (= "test-build-test-job"
           (->> {:build {:checkout-dir "/tmp"}
                 :sid ["test-cust" "test-repo" "test-build"]
                 :job {:id "test-job"}}
                (sut/instance-config)
                :display-name))))

  (testing "has tags from sid"
    (let [tags (->> {:build {:checkout-dir "/tmp"}
                     :sid ["test-cust" "test-repo" "test-build"]
                     :job {:id "test-job"}}
                    (sut/instance-config)
                    :freeform-tags)]
      (is (= "test-cust" (get tags "customer-id")))
      (is (= "test-repo" (get tags "repo-id")))
      (is (= "test-build" (get tags "build-id")))
      (is (= "test-job" (get tags "job-id")))))

  (testing "merges in existing tags"
    (let [tags (->> {:build {:checkout-dir "/tmp"}
                     :sid ["test-cust" "test-repo"]
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
        (is (= ["/bin/sh" (str cc/script-dir "/job.sh")] (:command jc)))
        (is (= ["0" "1"] (:arguments jc))))

      (testing "includes a script volume"
        (let [v (oci/find-mount jc "scripts")]
          (is (some? v))
          (is (= cc/script-dir (:mount-path v)))))

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
                   :build {:customer-id "test-cust"
                           :repo-id "test-repo"
                           :build-id "test-build"
                           :checkout-dir "/tmp/test-checkout"
                           :workspace "test-build-ws"}
                   :events {:type :manifold}
                   :api {:url "http://test-api"
                         :token "test-token"}
                   :oci {:credentials {:private-key pk}}}
                  (sut/instance-config))
          sc (->> ic
                  :containers
                  (mc/find-first (cp/prop-pred :display-name "sidecar")))
          cmd (:command sc)]

      (testing "invokes java"
        (is (= "java" (first cmd))))

      (testing "passes config file as arg"
        (is (h/contains-subseq? cmd
                                ["-c" "/home/monkeyci/config/config.edn"])))
      
      (testing "starts sidecar"
        (is (h/contains-subseq? cmd ["sidecar"])))

      (testing "passes events-file as arg"
        (is (h/contains-subseq? cmd
                                ["--events-file" cc/event-file])))

      (testing "passes start-file as arg"
        (is (h/contains-subseq? cmd
                                ["--start-file" cc/start-file])))

      (testing "passes abort-file as arg"
        (is (h/contains-subseq? cmd
                                ["--abort-file" cc/abort-file])))

      (testing "config volume"
        (let [mnt (oci/find-mount sc "config")
              v (oci/find-volume ic "config")]
          
          (testing "included in config"
            (is (some? mnt))
            (is (some? v))
            (is (= cc/config-dir (:mount-path mnt))))

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
                (is (= cc/work-dir (-> (cs/build data)
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
                   :sid (repeatedly 3 cuid/random-cuid)
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
          (is (= (str cc/log-dir "/*.log")
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

(deftest make-routes
  (let [routes (sut/make-routes {} (h/gen-build))
        expected [:container/job-queued
                  :container/start
                  :container/end
                  :sidecar/end
                  :job/executed]]
    (doseq [t expected]
      (testing (format "handles `%s`" t)
        (is (contains? (set (map first routes)) t))))))

(deftest prepare-instance-config
  (let [{:keys [enter] :as i} sut/prepare-instance-config]
    (is (keyword? (:name i)))

    (testing "adds ci config to context"
      (is (map? (-> {}
                    (sut/set-config default-config)
                    (sut/set-build {:checkout-dir "test/dir"})
                    (enter)
                    (oci/get-ci-config)))))))

(deftest end-on-ci-failure
  (let [{:keys [enter] :as i} sut/end-on-ci-failure]
    (is (keyword? (:name i)))
    
    (testing "set job/end event in result"
      (let [r (-> {:event
                   {:type :container/job-queued
                    :job-id "test-job"
                    :sid ["test" "sid"]}}
                  (oci/set-ci-response {:status 400
                                        :body
                                        {:message "test error"}})
                  (enter)
                  (em/get-result)
                  first)]
        (is (some? r))
        (is (= :job/end (:type r)))
        (is (= :failure (:status r)))
        (is (cstr/includes? (-> r :result :message) "test error"))))))

(deftest calc-credit-multiplier
  (let [{:keys [enter] :as i} sut/calc-credit-multiplier]
    (is (keyword? (:name i)))
    
    (testing "calculates from instance config"
      (is (= 5 (-> {}
                   (oci/set-ci-config {:shape "CI.Standard.E4.Flex"
                                       :shape-config
                                       {:ocpus 1
                                        :memory-in-g-bs 3}})
                   (enter)
                   (sut/get-credit-multiplier)))))))

(deftest save-instance-id
  (let [{:keys [enter] :as i} sut/save-instance-id]
    (is (keyword? (:name i)))

    (testing "stores response id in job state"
      (let [ocid (random-uuid)]
        (is (= ocid
               (-> {:event {:job-id "test-job"}}
                   (oci/set-ci-response {:body {:id ocid}})
                   (enter)
                   (sut/get-instance-id))))))))

(deftest job-queued
  (testing "fires `job/initializing` event with credit multiplier from context"
    (let [evts (-> {:event
                    {:job-id "container-job"}}
                   (sut/set-credit-multiplier 4)
                   (sut/job-queued))]
      (is (= [:job/initializing]
             (map :type evts)))
      (is (= 4 (-> evts first :credit-multiplier))))))

(deftest container-start
  (testing "fires `job/start` event"
    (is (= [:job/start]
           (->> {:event
                 {:job-id "test-job"}}
                (sut/container-start)
                (map :type))))))
