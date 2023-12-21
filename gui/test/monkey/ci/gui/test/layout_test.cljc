(ns monkey.ci.gui.test.layout-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is]]
               :clj [clojure.test :refer [deftest testing is]])
            [monkey.ci.gui.layout :as sut]))

(deftest welcome
  (testing "renders welcome panel"
    (is (vector? (sut/welcome [:p "Child panel"])))))
