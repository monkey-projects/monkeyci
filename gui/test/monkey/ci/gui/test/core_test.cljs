(ns monkey.ci.gui.test.core-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [monkey.ci.gui.core :as sut]))

(deftest main
  (testing "renders hiccup form"
    (is (vector? (sut/main)))))
