(ns monkey.ci.events.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.events.core :as sut]))

(deftest make-event
  (testing "adds timestamp to event"
    (is (number? (-> (sut/make-event :test-event :key "value")
                     :time))))

  (testing "adds properties from varargs"
    (is (= {:key "value"} (-> (sut/make-event :test-event :key "value")
                              (select-keys [:key])))))

  (testing "adds properties from map"
    (is (= {:key "value"} (-> (sut/make-event :test-event {:key "value"})
                              (select-keys [:key]))))))


