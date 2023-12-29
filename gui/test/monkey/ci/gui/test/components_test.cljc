(ns monkey.ci.gui.test.components-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.components :as sut]))

(deftest breadcrumb
  (testing "renders breadcrumb component"
    (is (vector? (sut/breadcrumb
                  [{:url "http://parent"
                    :name "parent"}
                   {:name "end"}])))))
