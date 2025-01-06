(ns monkey.ci.gui.test.admin.credits.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.admin.credits.subs :as sut]
            [monkey.ci.gui.admin.credits.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)
(rf/clear-subscription-cache!)

(deftest customers-loading?
  (let [l (rf/subscribe [:credits/customers-loading?])]
    (testing "exists"
      (is (some? l)))

    (testing "initially false"
      (is (false? @l)))
    
    (testing "true if loading by id"
      (is (some? (reset! app-db (lo/set-loading {} db/cust-by-id))))
      (is (true? @l)))

    (testing "true if loading by name"
      (is (some? (reset! app-db (lo/set-loading {} db/cust-by-name))))
      (is (true? @l)))))

(deftest customers
  (let [c (rf/subscribe [:credits/customers])]
    (testing "exists"
      (is (some? c)))

    (testing "initially empty"
      (is (empty? @c)))
    
    (testing "contains customers by id"
      (is (some? (reset! app-db (lo/set-value {} db/cust-by-id [::cust]))))
      (is (= [::cust])))

    (testing "contains customers by name"
      (is (some? (reset! app-db (lo/set-value {} db/cust-by-name [::cust]))))
      (is (= [::cust])))))

(deftest customers-loaded?
  (h/verify-sub
   [:credits/customers-loaded?]
   #(lo/set-loaded % db/cust-by-id)
   true
   false))

(deftest credits
  (h/verify-sub
   [:credits/credits]
   #(lo/set-value % db/credits [::test-credits])
   [::test-credits]
   nil))

(deftest credits-loading?
  (h/verify-sub
   [:credits/credits-loading?]
   #(lo/set-loading % db/credits)
   true
   false))

(deftest credit-alerts
  (h/verify-sub
   [:credits/credit-alerts]
   #(db/set-credit-alerts % ::test-alerts)
   ::test-alerts
   nil))

(deftest saving?
  (h/verify-sub
   [:credits/saving?]
   db/set-saving
   true
   false))
