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

  (testing "total millis between start time and last step end time"
    (let [b {:timestamp 10
             :pipelines
             [{:startTime 11
               :steps
               [{:startTime 12
                 :endTime 100}]}
              {:startTime 110
               :steps
               [{:startTime 120
                 :endTime 200}]}]}
          e (sut/build-elapsed b)]
      (is (= 190 e)))))
