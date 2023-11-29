(ns monkey.ci.test.containers.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.containers :as mcc]
            [monkey.ci.containers.oci :as sut]))

(deftest run-container
  (testing "can run using type `oci`"
    (is (some? (mcc/run-container {:containers {:type :oci}})))))
