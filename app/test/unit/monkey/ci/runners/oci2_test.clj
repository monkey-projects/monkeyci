(ns monkey.ci.runners.oci2-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [monkey.ci
             [edn :as edn]
             [oci :as oci]]
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
  (let [ic (sut/instance-config {:log-config "test-log-config"} {:build-id "test-build"})
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
                   (first))]
        (is (some? c))

        (testing "invokes script"
          (is (= "bash" (first (:arguments c)))))

        (let [config-env (get-in c [:environment-variables "CLJ_CONFIG"])]
          (testing "sets `CLJ_CONFIG` location"
            (is (some? config-env)))

          (testing "mounts script to `CLJ_CONFIG`"
            (let [vm (oci/find-mount c "script")]
              (is (some? vm))
              (is (= config-env (:mount-path vm))))))))

    (testing "volumes"
      (testing "contains config"
        (let [vol (oci/find-volume ic "config")]
          (is (some? vol))
          
          (testing "contains `config.edn`"
            (let [conf (some-> (decode-vol-config vol "config.edn")
                               (edn/edn->))]
              (is (some? conf)
                  "should contain config file")
              (is (some? (:build conf))
                  "config should contain build")))

          (testing "contains `logback.xml`"
            (let [f (decode-vol-config vol "logback.xml")]
              (is (= "test-log-config" f))))))

      (testing "contains script"
        (let [vol (oci/find-volume ic "script")]
          (is (some? vol))

          (testing "contains `deps.edn`"
            (let [deps (some-> (decode-vol-config vol "deps.edn")
                               (edn/edn->))]
              (is (some? deps)))))))))

