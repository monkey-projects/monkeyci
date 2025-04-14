(ns monkey.ci.agent.runtime-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.agent.runtime :as sut]
            [monkey.ci.test.config :as tc]))

(deftest make-system
  (let [conf tc/base-config
        sys (sut/make-system conf)]
    (testing "provides build atom"
      (is (some? (:builds sys))))

    (testing "provides mailman event handler"
      (is (some? (:mailman sys))))

    (testing "provides global mailman event handler"
      (is (some? (:global-mailman sys))))

    (testing "provides artifacts"
      (is (some? (:artifacts sys))))

    (testing "provides cache"
      (is (some? (:cache sys))))

    (testing "provides workspace"
      (is (some? (:workspace sys))))

    (testing "provides api server"
      (is (some? (:api-server sys))))

    (testing "provides git clone fn"
      (is (fn? (-> sys :git :clone))))))
