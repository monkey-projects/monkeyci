(ns monkey.ci.containers.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [config :as c]
             [containers :as mcc]
             [oci :as oci]
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

  (testing "contains job and sidecar containers"
    (is (= #{"job" "sidecar"}
           (->> (sut/instance-config {} default-rt)
                :containers
                (map :display-name)
                (set)))))

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

  (testing "both containers have checkout volume"
    (letfn [(has-checkout-vol? [c]
              (some (comp (partial = oci/checkout-vol) :volume-name)
                    (:volume-mounts c)))]
      (is (every? has-checkout-vol? (:containers (sut/instance-config {} default-rt))))))

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
      
      (testing "starts sidecar using first arg"
        (is (= "sidecar" (first (:arguments sc)))))

      (testing "passes events-file as arg"
        (is (h/contains-subseq? (:arguments sc)
                                ["--events-file" sut/event-file])))

      (testing "passes start-file as arg"
        (is (h/contains-subseq? (:arguments sc)
                                ["--start-file" sut/start-file])))

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
                           :work-dir))))))))

      (testing "env vars"
        (let [env (:environment-variables sc)]

          (testing "specified in container"
            (is (some? env)))

          (testing "receives env vars from runtime"
            (is (= "test-build" (get env "MONKEYCI_BUILD_BUILD_ID"))))

          (testing "serializes private key to pem"
            (let [pk (get env "MONKEYCI_OCI_CREDENTIALS_PRIVATE_KEY")]
              (is (string? pk))
              (is (some? (u/load-privkey pk)))))

          (testing "removes initial args"
            (not (contains? env "MONKEYCI_ARGS")))

          (testing "overwrites build checkout dir"
            (is (= (str sut/work-dir "/test-checkout") (get env "MONKEYCI_BUILD_CHECKOUT_DIR"))))
          
          (testing "sets work dir to checkout dir"
            (is (= (str sut/work-dir "/test-checkout") (get env "MONKEYCI_WORK_DIR"))))

          (testing "recalculates script dir relative to new checkout dir")

          (testing "disables event server"
            (not (contains? env "MONKEYCI_EVENTS_SERVER_ENABLED")))

          (testing "passes other event properties"
            (is (= (get-in conf [:events :client :address])
                   (get env "MONKEYCI_EVENTS_CLIENT_ADDRESS"))))))

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
               (get-in by-name ["1" :data])))))))

(deftest wait-for-sidecar-end-event
  (testing "returns a deferred that holds the sidecar end event"
    (let [events (ec/make-events {:events {:type :manifold}})
          sid (repeatedly 3 random-uuid)
          d (sut/wait-for-sidecar-end-event events sid "this-job")]
      (is (md/deferred? d))
      (is (not (md/realized? d)) "should not be realized initially")
      (is (some? (ec/post-events events [{:type :sidecar/start
                                          :sid sid}])))
      (is (not (md/realized? d)) "should not be realized after start event")
      (is (some? (ec/post-events events [{:type :sidecar/end
                                          :sid sid
                                          :job {:id "other-job"}}])))
      (is (not (md/realized? d)) "should not be realized for other job")
      (is (some? (ec/post-events events [{:type :sidecar/end
                                          :sid sid
                                          :job {:id "this-job"}}])))
      (is (= :sidecar/end (-> (deref d 100 :timeout)
                              :type))))))

(deftest run-container
  (testing "can run using type `oci`, returns zero exit code on success"
    (with-redefs [oci/run-instance (constantly (md/success-deferred
                                                {:status 200
                                                 :body {:containers [{:exit-code 0}]}}))]
      (is (= 0 (-> (mcc/run-container {:containers {:type :oci}
                                       :build {:checkout-dir "/tmp"}})
                   (deref)
                   :exit)))))

  (testing "returns first nonzero exit code"
    (with-redefs [oci/run-instance (constantly (md/success-deferred
                                                {:status 200
                                                 :body {:containers [{:exit-code 0}
                                                                     {:exit-code 123}]}}))]
      (is (= 123 (-> (mcc/run-container {:containers {:type :oci}
                                         :build {:checkout-dir "/tmp"}})
                     (deref)
                     :exit))))))

(deftest normalize-key
  (testing "merges with oci"
    (is (= {:type :oci
            :key "value"}
           (->> {:containers {:type :oci}
                 :oci {:key "value"}}
                (c/normalize-key :containers)
                :containers)))))
