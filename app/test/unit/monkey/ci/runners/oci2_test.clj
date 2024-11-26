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

(deftest instance-config
  (let [ic (sut/instance-config {} {:build-id "test-build"})
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
            (is (= "java" (first args)))
            (is (some? (->> args
                            (filter (partial = "monkey.ci.runners.oci2"))
                            (first))))))

        (testing "has config volume mount"
          (let [vm (oci/find-mount c "config")]
            (is (some? vm))
            (is (= "/home/monkeyci/config" (:mount-path vm)))))))

    (testing "build"
      (let [c (->> co
                   (filter (comp (partial = "build") :display-name))
                   (first))]
        (is (some? c))

        (testing "invokes clojure process"
          (is (= "clojure" (first (:arguments c)))))))

    (testing "volumes"
      (testing "contains config"
        (let [vol (oci/find-volume ic "config")
              conf (some->> vol
                            :configs
                            (filter (comp (partial = "config.edn") :file-name))
                            first
                            :data
                            (h/base64->)
                            (edn/edn->))]
          (is (some? vol))
          (is (some? conf)
              "should contain config file")
          (is (some? (:build conf))
              "config should contain build"))))))

(deftest run-controller
  (h/with-tmp-dir dir
    (let [run-path (str dir "/test.run")
          rt (-> (trt/test-runtime)
                 (assoc :run-path run-path))]
      (testing "performs git clone")
      (testing "restores build cache")
      (testing "creates run file")
      (testing "starts api server")
      (testing "waits until run file has been deleted"))))

(deftest controller-main
  (testing "invokes `run-controller` with runtime created from config"
    (h/with-tmp-dir dir
      (let [config (str (fs/path dir "config.edn"))]
        (is (nil? (spit config (pr-str tc/base-config))))
        (with-redefs [sut/run-controller (fn [rt]
                                           (when (some? rt) ::ok))]
          (is (= ::ok (sut/-main config))))))))
