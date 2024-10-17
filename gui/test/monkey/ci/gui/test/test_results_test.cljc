(ns monkey.ci.gui.test.test-results-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.test-results :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(rf/clear-subscription-cache!)

(use-fixtures :each f/reset-db)

(deftest selected-test
  (let [s (rf/subscribe [:test/selected])]
    (testing "sub exists"
      (is (some? s)))
    
    (testing "sets current test"
      (is (nil? @s))
      (rf/dispatch-sync [:test/select ::current])
      (is (= ::current @s)))))

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
  (let [id (str (random-uuid))
        s (rf/subscribe [::sut/timing-chart-form id])]
    
    (testing "updates selected suite"
      (is (nil? (rf/dispatch-sync [::sut/timing-chart-changed id :suite "test suite"])))
      (is (= "test suite" (:suite @s))))

    (testing "updates selected count"
      (is (nil? (rf/dispatch-sync [::sut/timing-chart-changed id :count 10])))
      (is (= 10 (:count @s))))

    (rft/run-test-sync
     (rf/reg-event-db
      :chart/update
      (fn [db evt]
        (assoc db ::updates (rest evt))))

     (is (nil? (rf/dispatch [::sut/timing-chart-init id
                             [{:name "suite-1"
                               :test-cases
                               [{:test-case "test-1"
                                 :time 1}]}
                              {:name "suite-2"
                               :test-cases
                               [{:test-case "test-2"
                                 :time 2}]}]])))
     
     (testing "updates chart for id"
       (is (nil? (rf/dispatch [::sut/timing-chart-changed id :count 10])))
       (is (= id (-> @app-db ::updates first))))

     (testing "filters chart data by suite"
       (is (nil? (rf/dispatch [::sut/timing-chart-changed id :suite "suite-2"])))
       (let [data (-> @app-db ::updates second)]
         (is (= 1 (-> data :data :datasets first :data count)))
         (is (= "test-2" (-> data :data :labels first))))))))
