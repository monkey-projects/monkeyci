(ns monkey.ci.build.api-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold.deferred :as md]
            [monkey.ci.build.api :as sut]))

(deftest build-params
  (testing "invokes `params` endpoint on client"
    (let [m (fn [req]
              (when (= "/customer/test-cust/repo/test-repo/param" (:url req))
                (md/success-deferred [{:name "key"
                                       :value "value"}])))
          rt {:api {:client m}
              :build {:sid ["test-cust" "test-repo" "test-build"]}}]
      (is (= {"key" "value"} (sut/build-params rt))))))
