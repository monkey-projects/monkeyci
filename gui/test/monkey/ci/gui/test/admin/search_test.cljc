(ns monkey.ci.gui.test.admin.search-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.admin.search :as sut]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)
(rf/clear-subscription-cache!)


(deftest customer-search
  (testing "searches for customer by name and id"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:search-customers {:status 200
                                                 :body "ok"
                                                 :error-code :no-error}})
       (rf/dispatch [:admin/customer-search {:customer-filter ["test-cust"]}])
       (is (= 2 (count @c))))))

  (testing "clears customers"
    (is (some? (reset! app-db (lo/set-value {} sut/cust-by-name [::initial-customers]))))
    (rf/dispatch-sync [:admin/customer-search {}])
    (is (empty? (sut/get-customers @app-db)))))

(deftest customer-search--success
  (testing "when by name, adds customers to db"
    (is (some? (reset! app-db (lo/set-value {} sut/cust-by-id [::initial]))))
    (rf/dispatch-sync [:admin/customer-search--success sut/cust-by-name {:body [::new]}])
    (is (= #{::initial ::new} (set (sut/get-customers @app-db)))))

  (testing "when by id, adds customers to db"
    (is (some? (reset! app-db (lo/set-value {} sut/cust-by-name [::initial]))))
    (rf/dispatch-sync [:admin/customer-search--success sut/cust-by-id {:body [::new]}])
    (is (= #{::initial ::new} (set (sut/get-customers @app-db))))))

(deftest customer-search--failed
  (testing "sets alert for id"
    (rf/dispatch-sync [:admin/customer-search--failed sut/cust-by-name "test error"])
    (is (= 1 (count (lo/get-alerts @app-db sut/cust-by-name))))))

(deftest customers-loading?
  (let [l (rf/subscribe [:admin/customers-loading?])]
    (testing "exists"
      (is (some? l)))

    (testing "initially false"
      (is (false? @l)))
    
    (testing "true if loading by id"
      (is (some? (reset! app-db (lo/set-loading {} sut/cust-by-id))))
      (is (true? @l)))

    (testing "true if loading by name"
      (is (some? (reset! app-db (lo/set-loading {} sut/cust-by-name))))
      (is (true? @l)))))

(deftest customers
  (let [c (rf/subscribe [:admin/customers])]
    (testing "exists"
      (is (some? c)))

    (testing "initially empty"
      (is (empty? @c)))
    
    (testing "contains customers by id"
      (is (some? (reset! app-db (lo/set-value {} sut/cust-by-id [::cust]))))
      (is (= [::cust])))

    (testing "contains customers by name"
      (is (some? (reset! app-db (lo/set-value {} sut/cust-by-name [::cust]))))
      (is (= [::cust])))))

(deftest customers-loaded?
  (h/verify-sub
   [:admin/customers-loaded?]
   #(lo/set-loaded % sut/cust-by-id)
   true
   false))
