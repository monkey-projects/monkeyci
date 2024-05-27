(ns monkey.ci.gui.test.test-results-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.test-results :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(rf/clear-subscription-cache!)

(use-fixtures :each f/reset-db)

(deftest timing-chart-form-sub
  (let [s (rf/subscribe [::sut/timing-chart-form ::test])]
    (testing "exists"
      (is (some? s)))

    (testing "defaults to all suites and top 5"
      (is (= {:count 5} @s)))))

(deftest timing-chart-val-sub
  (let [s (rf/subscribe [::sut/timing-chart-val ::test :count])]
    (testing "exists"
      (is (some? s)))

    (testing "returns form value"
      (is (= 5 @s)))))

(deftest timing-chart-changed-evt
  (let [id (random-uuid)
        s (rf/subscribe [::sut/timing-chart-form id])]
    
    (testing "updates selected suite"
      (rf/dispatch-sync [::sut/timing-chart-changed id :suite "test suite"])
      (is (= "test suite" (:suite @s))))

    (testing "updates selected count"
      (rf/dispatch-sync [::sut/timing-chart-changed id :count 10])
      (is (= 10 (:count @s))))))
