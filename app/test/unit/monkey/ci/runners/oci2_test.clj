(ns monkey.ci.runners.oci2-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [medley.core :as mc]
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
  (let [build {:build-id "test-build"
               :git {:ssh-keys [{:private-key "test-privkey"
                                 :public-key "test-pubkey"}]}}
        ic (sut/instance-config {:log-config "test-log-config"
                                 :build-image-url "test-clojure-img"}
                                build
                                (trt/test-runtime))
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
            (is (= "controller" (last args)))))

        (testing "has config volume mount"
          (let [vm (oci/find-mount c "config")]
            (is (some? vm))
            (is (= "/home/monkeyci/config" (:mount-path vm)))))

        (testing "has checkout volume mount"
          (is (some? (oci/find-mount c oci/checkout-vol))))

        (testing "has ssh keys volume mount"
          (is (some? (oci/find-mount c "ssh-keys"))))))

    (testing "build"
      (let [c (->> co
                   (filter (comp (partial = "build") :display-name))
                   (first))
            env (:environment-variables c)]
        (is (some? c))

        (testing "uses configured image url"
          (is (= "test-clojure-img" (:image-url c))))

        (testing "invokes script"
          (is (= "bash" (first (:command c)))))

        (let [config-env (get env "CLJ_CONFIG")]
          (testing "sets `CLJ_CONFIG` location"
            (is (some? config-env)))

          (testing "mounts script to `CLJ_CONFIG`"
            (let [vm (oci/find-mount c "script")]
              (is (some? vm))
              (is (= config-env (:mount-path vm))))))

        (testing "sets work dir to script dir"
          (is (= (str oci/work-dir "/" (:build-id build) "/.monkeyci") (get env "MONKEYCI_WORK_DIR"))))

        (testing "sets run file"
          (is (= (str oci/checkout-dir "/" (:build-id build) ".run")
                 (get env "MONKEYCI_START_FILE"))))

        (testing "sets abort file"
          (is (= (str oci/checkout-dir "/" (:build-id build) ".abort")
                 (get env "MONKEYCI_ABORT_FILE"))))

        (testing "has checkout volume mount"
          (is (some? (oci/find-mount c oci/checkout-vol))))))

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
                (is (= (str oci/work-dir "/" (:build-id build))
                       (get-in conf [:build :git :dir]))))

              (testing "with build checkout dir"
                (is (some? (get-in conf [:build :checkout-dir]))))
              
              (testing "with ssh keys dir"
                (is (some? (get-in conf [:build :git :ssh-keys-dir]))))

              (testing "with api token and port"
                (is (number? (get-in conf [:runner :api-port])))
                (is (string? (get-in conf [:runner :api-token]))))

              (testing "with public api token"
                (is (string? (get-in conf [:api :token]))))

              (testing "with `checkout-base-dir`"
                (is (= oci/work-dir (:checkout-base-dir conf))))))

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

            (testing "provides monkeyci dependency"
              (is (string? (get-in deps [:aliases :monkeyci/build :extra-deps 'com.monkeyci/app :mvn/version]))))

            (let [sc (get-in deps [:aliases :monkeyci/build :exec-args :config])]
              (testing "passes script config as exec arg"
                (is (map? sc)))

              (testing "contains build"
                (let [r (cs/build sc)]
                  (is (= (:build-id build) (:build-id r)))
                  
                  (testing "without ssh keys"
                    (is (nil? (get-in r [:git :ssh-keys]))))

                  (testing "with credit multiplier"
                    (is (number? (:credit-multiplier r))))))

              (testing "contains api url and token"
                (let [api (cs/api sc)]
                  (is (string? (:url api)))
                  (is (string? (:token api))))))

            (testing "points to logback config file"
              (is (re-matches #"^-Dlogback\.configurationFile=.*$"
                              (-> (get-in deps [:aliases :monkeyci/build :jvm-opts])
                                  first))))

            (testing "sets m2 cache dir"
              (is (string? (:mvn/local-repo deps)))))

          (testing "contains `build.sh`"
            (is (some? (decode-vol-config vol "build.sh"))))))

      (testing "contains log config"
        (is (some? (oci/find-volume ic "log-config"))))

      (testing "contains ssh keys"
        (is (some? (oci/find-volume ic "ssh-keys")))))))

(deftest make-runner
  (testing "creates runner fn for type `oci2`"
    (is (fn? (r/make-runner {:runner
                             {:type :oci2}})))))
