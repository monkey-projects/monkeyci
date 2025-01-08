(ns monkey.ci.gui.test.admin.credits.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.admin.credits.events :as sut]
            [monkey.ci.gui.admin.credits.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest credits-customer-search
  (testing "searches for customer by name and id"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:search-customers {:status 200
                                                 :body "ok"
                                                 :error-code :no-error}})
       (rf/dispatch [:credits/customer-search {:customer-filter ["test-cust"]}])
       (is (= 2 (count @c))))))

  (testing "clears customers"
    (is (some? (reset! app-db (lo/set-value {} db/cust-by-name [::initial-customers]))))
    (rf/dispatch-sync [:credits/customer-search {}])
    (is (empty? (db/get-customers @app-db)))))

(deftest credits-customer-search--success
  (testing "when by name, adds customers to db"
    (is (some? (reset! app-db (lo/set-value {} db/cust-by-id [::initial]))))
    (rf/dispatch-sync [:credits/customer-search--success db/cust-by-name {:body [::new]}])
    (is (= #{::initial ::new} (set (db/get-customers @app-db)))))

  (testing "when by id, adds customers to db"
    (is (some? (reset! app-db (lo/set-value {} db/cust-by-name [::initial]))))
    (rf/dispatch-sync [:credits/customer-search--success db/cust-by-id {:body [::new]}])
    (is (= #{::initial ::new} (set (db/get-customers @app-db))))))

(deftest credits-customer-search--failed
  (testing "sets alert for id"
    (rf/dispatch-sync [:credits/customer-search--failed db/cust-by-name "test error"])
    (is (= 1 (count (lo/get-alerts @app-db db/cust-by-name))))))

(deftest credits-load
  (testing "fetches customer credit overview from backend"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-customer-credit-overview
                              {:status 200
                               :body []
                               :error-code :no-error}})
       (rf/dispatch [:credits/load {:customer-id "test-cust"}])
       (is (= 1 (count @c)))))))

(deftest credits-load--success
  (testing "stores credits in db"
    (rf/dispatch-sync [:credits/load--success {:body [::test-credits]}])
    (is (= [::test-credits] (db/get-credits @app-db)))))

(deftest credits-load--failed
  (testing "sets alert"
    (rf/dispatch-sync [:credits/load--failed "test error"])
    (is (= 1 (count (db/get-credit-alerts @app-db))))))

(deftest credits-new
  (testing "displays form"
    (rf/dispatch-sync [:credits/new])
    (is (true? (db/show-credits-form? @app-db)))))

(deftest credits-save
  (rf-test/run-test-sync
   (let [c (h/catch-fx :martian.re-frame/request)]
     (is (some? (reset! app-db (r/set-current {} {:parameters
                                                  {:path
                                                   {:customer-id "test-cust"}}}))))
     (h/initialize-martian {:issue-credits
                            {:status 200
                             :body {}
                             :error-code :no-error}})
     (rf/dispatch [:credits/save {:amount [1000]
                                  :reason ["test reason"]
                                  :from-time ["2025-01-01"]}])
     (testing "saves to backend"
       (is (= 1 (count @c)))
       (is (= :issue-credits (-> @c first (nth 2)))))

     (testing "passes form params as body"
       (let [params (-> @c first (nth 3) :credits)]
         (is (= {:amount 1000
                 :reason "test reason"}
                (select-keys params [:amount :reason])))
         (is (number? (:from-time params)))))

     (testing "adds customer id"
       (is (= "test-cust"
              (-> @c first (nth 3) :customer-id))))

     (testing "marks saving"
       (is (db/saving? @app-db))))))

(deftest credits-save--success
  (let [cred {:id "test-cred"
              :amount 1000}]
    (is (some? (reset! app-db (db/set-saving {}))))
    (rf/dispatch-sync [:credits/save--success {:body cred}])
    
    (testing "adds credits to db"
      (is (= [cred] (db/get-credits @app-db))))

    (testing "unmarks saving"
      (is (not (db/saving? @app-db))))

    (testing "sets success alert")))

(deftest credits-save--failed
  (is (some? (reset! app-db (db/set-saving {}))))
  
  (testing "sets alert"
    (rf/dispatch-sync [:credits/save--failed "test error"])
    (is (= 1 (count (db/get-credit-alerts @app-db)))))

  (testing "unmarks saving"
    (is (not (db/saving? @app-db)))))

(deftest credits-cancel
  (testing "hides credits form"
    (is (some? (reset! app-db (db/show-credits-form {}))))
    (rf/dispatch-sync [:credits/cancel])
    (is (not (db/show-credits-form? @app-db)))))
