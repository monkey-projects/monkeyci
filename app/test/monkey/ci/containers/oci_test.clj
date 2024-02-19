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
            [monkey.ci.helpers :as h]))

(deftest instance-config
  (testing "creates configuration map"
    (let [ic (sut/instance-config {} {})]
      (is (map? ic))
      (is (string? (:shape ic)))))

  (testing "contains job and sidecar containers"
    (is (= #{"job" "sidecar"}
           (->> (sut/instance-config {} {})
                :containers
                (map :display-name)
                (set)))))

  (testing "display name contains build id, pipeline name and step index"
    (is (= "test-build-test-pipeline-1"
           (->> {:build {:build-id "test-build"}
                 :step {:pipeline "test-pipeline"
                        :index 1}}
                (sut/instance-config {})
                :display-name))))

  (testing "has tags from sid"
    (let [tags (->> {:build {:sid ["test-cust" "test-proj" "test-repo"]}}
                    (sut/instance-config {})
                    :tags)]
      (is (= "test-cust" (get tags "customer-id")))
      (is (= "test-proj" (get tags "project-id")))
      (is (= "test-repo" (get tags "repo-id")))))

  (testing "both containers have checkout volume"
    (letfn [(has-checkout-vol? [c]
              (some (comp (partial = oci/checkout-vol) :volume-name)
                    (:volume-mounts c)))]
      (is (every? has-checkout-vol? (:containers (sut/instance-config {} {}))))))

  (testing "job container"
    (let [jc (->> {:step {:script ["first" "second"]}}
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
          (testing "sets work dir"
            (is (string? (get env "WORK_DIR"))))
          
          (testing "sets log dir"
            (is (string? (get env "LOG_DIR"))))

          (testing "sets script dir"
            (is (string? (get env "SCRIPT_DIR"))))))))

  (testing "sidecar container"
    (let [pk (h/generate-private-key)
          sc (->> {:step {:script ["first" "second"]}
                   :build {:build-id "test-build"}
                   :config {:original {:oci {:credentials {:private-key "local/private.key"}}}}}
                  (sut/instance-config {:credentials {:private-key pk}})
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

      (testing "includes a config volume"
        (let [v (oci/find-mount sc "config")]
          (is (some? v))
          (is (= sut/config-dir (:mount-path v)))))

      (testing "includes private key volume"
        (let [v (oci/find-mount sc "private-key")]
          (is (some? v))
          (is (= "/opt/monkeyci/keys" (:mount-path v)))))

      (testing "receives env vars from runtime"
        (is (some? (:environment-variables sc)))
        (is (= "test-build" (-> sc
                                :environment-variables
                                (get "MONKEYCI_BUILD_BUILD_ID")))))

      (testing "rewrites OCI credentials private key to point to mounted file"
        (is (= "/opt/monkeyci/keys/privkey" (-> sc
                                                :environment-variables
                                                (get "MONKEYCI_OCI_CREDENTIALS_PRIVATE_KEY")))))

      (testing "runs as root to access mount volumes"
        (is (= 0 (-> sc :security-context :run-as-user)))))

    (testing "adds logback config file to config mount"
      (h/with-tmp-dir dir
        (let [p (io/file dir "logback.xml")]
          (spit p "test logback configuration")
          (let [vol (->> {:step {:script ["test"]}
                          :config {:sidecar {:log-config (.getCanonicalPath p)}}}
                         (sut/instance-config {})
                         :volumes
                         (filter (comp (partial = "config") :name))
                         (first))]
            (is (some? vol))
            (is (= 1 (count (:configs vol))))
            (is (= "logback.xml" (-> vol :configs first :file-name))))))))

  (testing "script volume"
    (let [v (->> {:step {:script ["first" "second"]}}
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

(deftest run-container
  (testing "can run using type `oci`, returns exit code"
    (with-redefs [oci/run-instance (constantly (md/success-deferred 123))]
      (is (= 123 (-> (mcc/run-container {:containers {:type :oci}})
                     :exit))))))

(deftest normalize-key
  (testing "merges with oci"
    (is (= {:type :oci
            :key "value"}
           (->> {:containers {:type :oci}
                 :oci {:key "value"}}
                (c/normalize-key :containers)
                :containers)))))
