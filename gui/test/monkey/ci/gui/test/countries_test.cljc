(ns monkey.ci.gui.test.countries-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.countries :as sut]))

(deftest countries
  (testing "holds list of country names and codes"
    (is (not-empty sut/countries))
    (is (every? (every-pred :name :code)
                sut/countries))))
