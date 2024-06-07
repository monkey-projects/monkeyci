(ns monkey.ci.gui.test.home.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.home.subs :as sut]
            [monkey.ci.gui.home.db :as db]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.test.fixtures :as f]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)
(rf/clear-subscription-cache!)

(deftest user-customers
  (let [uc (rf/subscribe [:user/customers])]
    (testing "exists"
      (is (some? uc)))

    (testing "returns user customers"
      (is (nil? @uc))
      (is (some? (reset! app-db (db/set-customers {} ::customers))))
      (is (= ::customers @uc)))))

(deftest alerts
  (let [a (rf/subscribe [:user/alerts])]
    (testing "exists"
      (is (some? a)))

    (testing "returns user alerts"
      (is (nil? @a))
      (is (some? (reset! app-db (db/set-alerts {} ::alerts))))
      (is (= ::alerts @a)))))

(deftest join-alerts
  (let [a (rf/subscribe [:customer/join-alerts])]
    (testing "exists"
      (is (some? a)))

    (testing "returns customer join alerts"
      (is (nil? @a))
      (is (some? (reset! app-db (db/set-join-alerts {} ::alerts))))
      (is (= ::alerts @a)))))

(deftest customer-searching?
  (let [s (rf/subscribe [:customer/searching?])]
    (testing "exists"
      (is (some? s)))

    (testing "returns customer search status"
      (is (false? @s))
      (is (some? (reset! app-db (db/set-customer-searching {} true))))
      (is (true? @s)))))

(deftest customer-search-results
  (let [r (rf/subscribe [:customer/search-results])]
    (testing "exists"
      (is (some? r)))

    (testing "returns customer search results"
      (is (nil? @r))
      (is (some? (reset! app-db (db/set-search-results {} ::results))))
      (is (= ::results @r)))))

(deftest customer-join-list
  (let [r (rf/subscribe [:customer/join-list])]
    (testing "exists"
      (is (some? r)))

    (testing "contains search results"
      (is (nil? @r))
      (is (some? (reset! app-db (db/set-search-results {} [{:id "test customer"}]))))
      (is (= ["test customer"] (map :id @r))))

    (testing "sets status to `:joined` if user is already linked"
      (is (some? (reset! app-db (-> {}
                                    (db/set-search-results [{:id "joined-cust"}])
                                    (ldb/set-user {:id "test-user"
                                                   :customers ["joined-cust"]})))))
      (is (= :joined (-> @r first :status))))

    (testing "sets status to `:joining` if join request is being sent"
      (let [cust-id "test-customer"]
        (is (some? (reset! app-db (-> {}
                                      (db/set-search-results [{:id "test-customer"}])
                                      (db/mark-customer-joining "test-customer")))))
        (is (= :joining (-> @r first :status)))))))

(deftest user-join-requests
  (let [r (rf/subscribe [:user/join-requests])]
    (testing "exists"
      (is (some? r)))

    (testing "returns join requests"
      (is (nil? @r))
      (is (some? (reset! app-db (db/set-join-requests {} ::results))))
      (is (= ::results @r)))))

(deftest customer-joining?
  (let [cust-id "test-customer"
        c (rf/subscribe [:customer/joining? cust-id])]
    (testing "exists"
      (is (some? c)))

    (testing "`true` if currently joining customer"
      (is (false? @c))
      (is (some? (reset! app-db (db/mark-customer-joining {} cust-id))))
      (is (true? @c)))

    (testing "returns all when no customer id given"
      (is (= #{cust-id} @(rf/subscribe [:customer/joining?]))))))
