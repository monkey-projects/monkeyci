(ns monkey.ci.runtime.app-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [monkey.ci
             [runtime :as rt]
             [protocols :as p]]
            [monkey.ci.containers.oci :as cco]
            [monkey.ci.runtime.app :as sut]
            [monkey.ci.spec.runner :as sr]
            [monkey.ci.test.config :as tc]
            [monkey.ci.helpers :as h]))

(def runner-config
  (assoc tc/base-config
         :checkout-base-dir "/base/dir"
         :build {:customer-id "test-cust"
                 :repo-id "test-repo"
                 :build-id "test-build"
                 :sid ["test" "build"]}
         :api {:url "http://test"
               :token "test-token"}))

(deftest make-runner-system
  (let [sys (sut/make-runner-system runner-config)]
    (testing "creates system map"
      (is (map? sys)))

    (testing "contains runtime"
      (is (sut/runtime? (:runtime sys))))

    (testing "generates api token and random port"
      (let [conf (:api-config sys)]
        (is (string? (:token conf)))
        (is (int? (:port conf)))))))

(deftest with-runner-runtime
  (let [sys (sut/with-runner-system runner-config identity)
        rt (:runtime sys)]
    
    (testing "runtime matches spec"
      (is (spec/valid? ::sr/runtime rt)
          (spec/explain-str ::sr/runtime rt)))
    
    (testing "provides events"
      (is (some? (:events rt))))

    (testing "provides artifacts"
      (is (some? (:artifacts rt))))

    (testing "provides cache"
      (is (some? (:cache rt))))

    (testing "provides containers"
      (is (some? (:containers rt))))

    (testing "passes api config to oci container runner"
      (let [sys (-> runner-config
                    (assoc :containers {:type :oci})
                    (sut/with-runner-system identity))]
        (with-redefs [cco/run-container :api]
          (is (= (get-in sys [:api-config :token])
                 (:token (p/run-container (:containers sys) {})))))))

    (testing "provides runner"
      (is (ifn? (:runner sys))))

    (testing "provides workspace"
      (is (some? (:workspace rt))))

    (testing "provides logging"
      (is (some? (rt/log-maker rt))))

    (testing "provides git"
      (is (fn? (get-in rt [:git :clone]))))

    (testing "adds config"
      (is (= runner-config (:config rt))))

    (testing "build"
      (let [build (:build rt)]
        (testing "sets workspace path"
          (is (string? (:workspace build))))

        (testing "calculates checkout dir"
          (is (= "/base/dir/test-build" (:checkout-dir build))))))

    (testing "starts api server at configured pod"
      (is (some? (get-in sys [:api-server :server])))
      (is (= (get-in sys [:api-server :port])
             (get-in sys [:api-config :port]))))))
