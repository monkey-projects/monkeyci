(ns monkey.ci.gui.test.utils-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is]]
               :clj [clojure.test :refer [deftest testing is]])
            [monkey.ci.gui.utils :as sut]))

(deftest ->sid
  (testing "creates sid string from values from map"
    (is (= "a/b/c"
           (-> {:first "a"
                :second "b"
                :third "c"}
               (sut/->sid :first :second :third))))))
