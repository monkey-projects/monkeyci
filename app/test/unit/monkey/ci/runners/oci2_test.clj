(ns monkey.ci.runners.oci2-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [monkey.ci
             [edn :as edn]
             [oci :as oci]
             [runners :as r]]
            [monkey.ci.config.script :as cs]
            [monkey.ci.runners.oci2 :as sut]
            [monkey.ci.helpers :as h]
            [monkey.ci.test
             [config :as tc]
             [runtime :as trt]]))

(defn- decode-vol-config [vol fn]
  (some->> vol
           :configs
           (filter (comp (partial = fn) :file-name))
           first
           :data
           (h/base64->)))

(deftest instance-config
  (let [build {:build-id "test-build"}
        ic (sut/instance-config {:log-config "test-log-config"}
                                build)
        co (:containers ic)]
    (testing "creates container instance configuration"
      (is (map? ic))
      (is (= "test-build" (:display-name ic))))

    (testing "contains two containers"
      (is (= 2 (count co)))
      (is (every? string? (map :image-url co))))

    (testing "containers run as root"
      (is (every? zero? (map (comp :run-as-user :security-context) co))))

    (testing "assigns freeform tags containing build info")

    (testing "controller"
      (let [c (->> co
                   (filter (comp (partial = "controller") :display-name))
                   (first))]
        (is (some? c))

        (testing "runs monkeyci controller main"
          (let [args (:arguments c)]
            (is (= "controller" (first args)))))

        (testing "has config volume mount"
          (let [vm (oci/find-mount c "config")]
            (is (some? vm))
            (is (= "/home/monkeyci/config" (:mount-path vm)))))))

    (testing "build"
      (let [c (->> co
                   (filter (comp (partial = "build") :display-name))
                   (first))
            env (:environment-variables c)]
        (is (some? c))

        (testing "invokes script"
          (is (= "bash" (first (:arguments c)))))

        (let [config-env (get env "CLJ_CONFIG")]
          (testing "sets `CLJ_CONFIG` location"
            (is (some? config-env)))

          (testing "mounts script to `CLJ_CONFIG`"
            (let [vm (oci/find-mount c "script")]
              (is (some? vm))
              (is (= config-env (:mount-path vm))))))

        (testing "sets work dir"
          (is (= oci/work-dir (get env "MONKEYCI_WORK_DIR"))))

        (testing "sets run file"
          (is (= (str oci/checkout-dir "/" (:build-id build) ".run")
                 (get env "MONKEYCI_START_FILE"))))

        (testing "sets abort file"
          (is (= (str oci/checkout-dir "/" (:build-id build) ".abort")
                 (get env "MONKEYCI_ABORT_FILE"))))))

    (testing "volumes"
      (testing "contains config"
        (let [vol (oci/find-volume ic "config")]
          (is (some? vol))
          
          (testing "contains `config.edn`"
            (let [conf (some-> (decode-vol-config vol "config.edn")
                               (edn/edn->))]
              (is (some? conf)
                  "should contain config file")

              (testing "with git checkout dir"
                (is (some? (get-in conf [:build :git :dir]))))

              (testing "with api token and port"
                (is (number? (get-in conf [:runner :api-port])))
                (is (string? (get-in conf [:runner :api-token]))))))

          (testing "contains `logback.xml`"
            (let [f (decode-vol-config vol "logback.xml")]
              (is (= "test-log-config" f))))))

      (testing "contains script"
        (let [vol (oci/find-volume ic "script")]
          (is (some? vol))

          (let [deps (some-> (decode-vol-config vol "deps.edn")
                             (edn/edn->))]
            (testing "contains `deps.edn`"
              (is (some? deps)))

            (let [sc (get-in deps [:aliases :monkeyci/build :exec-args :config])]
              (testing "passes script config as exec arg"
                (is (map? sc)))

              (testing "contains build"
                (is (= build (select-keys (cs/build sc) (keys build)))))

              (testing "contains api url and token"
                (let [api (cs/api sc)]
                  (is (string? (:url api)))
                  (is (string? (:token api)))))))

          (testing "contains `build.sh`"
            (is (some? (decode-vol-config vol "build.sh")))))))))

(deftest make-runner
  (testing "creates runner fn for type `oci2`"
    (is (fn? (r/make-runner {:runner
                             {:type :oci2}})))))
