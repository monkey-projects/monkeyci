(ns monkey.ci.hook.test.index-test
  (:require [cljs.test :refer [deftest testing is]]
            [monkey.ci.hook.index :as sut]))

(deftest main
  (testing "returns nil"
    (is (nil? (sut/main)))))
