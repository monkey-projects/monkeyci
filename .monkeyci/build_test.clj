(ns monkeyci.build.script-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkeyci.build.script :as sut]))

(deftest tag-version
  (testing "returns something"
    (is (string? (sut/tag-version {})))))
