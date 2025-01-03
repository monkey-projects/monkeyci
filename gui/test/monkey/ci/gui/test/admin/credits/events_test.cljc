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
