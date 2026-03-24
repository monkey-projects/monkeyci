(ns monkey.ci.plans-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.plans :as sut]))

(deftest validate-plan
  (testing "starter"
    (let [v (sut/validate-plan {:type :starter})]
      (testing "sets max users"
        (is (= 3 (:max-users v))))
      
      (testing "sets credits"
        (is (= 5000 (:credits v))))))

  (testing "pro"
    (testing "sets credits"
      (is (= 30000 (-> (sut/validate-plan {:type :pro :max-users 10})
                       :credits))))

    (testing "fails when no users"
      (is (thrown? Exception (sut/validate-plan {:type :pro}))))))
