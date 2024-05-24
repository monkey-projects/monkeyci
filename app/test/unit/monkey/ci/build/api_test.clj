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

(deftest download-artifact
  (testing "invokes artifact download endpoint on client"
    (let [m (fn [req]
              (when (= "/customer/test-cust/repo/test-repo/builds/test-build/artifact/test-artifact/download"
                       (:url req))
                (md/success-deferred "test artifact contents")))
          rt {:api {:client m}
              :build {:sid ["test-cust" "test-repo" "test-build"]}}]
      (is (= "test artifact contents"
             (sut/download-artifact rt "test-artifact"))))))
