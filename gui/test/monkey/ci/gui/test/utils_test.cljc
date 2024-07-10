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

(deftest build-elapsed
  (testing "0 if empty"
    (is (= 0 (sut/build-elapsed {}))))

  (testing "total millis between start time and end time"
    (let [b {:start-time 10
             :end-time 100}
          e (sut/build-elapsed b)]
      (is (= 90 e)))))

(deftest pluralize
  (testing "returns first arg if singular"
    (is (= "single" (sut/pluralize "single" 1))))

  (testing "returns pluralized arg if multiple or zero"
    (is (= "singles" (sut/pluralize "single" 10)))
    (is (= "singles" (sut/pluralize "single" 0))))

  (testing "returns plural if specified"
    (is (= "cities" (sut/pluralize "city" 2 "cities")))))

(deftest update-nth
  (testing "applies `f` to the nth item in the list"
    (is (= [0 2 2]
           (sut/update-nth [0 1 2] 1 inc)))))
