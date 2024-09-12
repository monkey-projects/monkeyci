(ns monkey.ci.gui.test.layout-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is]]
               :clj [clojure.test :refer [deftest testing is]])
            [monkey.ci.gui.layout :as sut]))

(deftest default
  (testing "renders contents in default layout"
    (is (vector? (sut/default [:p "Child panel"])))))
