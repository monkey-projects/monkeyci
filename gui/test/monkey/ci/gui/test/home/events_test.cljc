(ns monkey.ci.gui.test.home.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.home.events :as sut]
            [monkey.ci.gui.home.db :as db]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest user-load-customers
  (testing "sends request to api"
    (rf-test/run-test-sync
     (let [cust [{:name "test customer"}]
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-user-customers {:status 200
                                                   :body cust
                                                   :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:user/load-customers])
       (is (= 1 (count @c)))
       (is (= :get-user-customers (-> @c first (nth 2)))))))

  (testing "sets alert"
    (rf/dispatch-sync [:user/load-customers])
    (is (some? (db/alerts @app-db)))))

(deftest user-load-customers--success
  (testing "sets customers in db"
    (rf/dispatch-sync [:user/load-customers--success {:body ::customers}])
    (is (= ::customers (db/customers @app-db))))

  (testing "clears alerts"
    (is (some? (reset! app-db (db/set-alerts {} [{:type :info}]))))
    (rf/dispatch-sync [:user/load-customers--success {:body ::customers}])
    (is (empty? (db/alerts @app-db)))))

(deftest user-load-customers--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:user/load-customers--failed {:message "test error"}])
    (is (= :danger (-> (db/alerts @app-db)
                       first
                       :type)))))

(deftest customer-search
  (testing "sends request to api"
    (rf-test/run-test-sync
     (let [cust [{:name "test customer"}]
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:search-customers {:status 200
                                                 :body cust
                                                 :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:customer/search {:customer-search ["test customer"]}])
       (is (= 1 (count @c)))
       (is (= :search-customers (-> @c first (nth 2)))))))

  (testing "marks searching in db"
    (rf/dispatch-sync [:customer/search {}])
    (is (true? (db/customer-searching? @app-db))))

  (testing "clears alerts"
    (is (some? (reset! app-db (db/set-join-alerts {} [{:type :info}]))))
    (rf/dispatch-sync [:customer/search {}])
    (is (nil? (db/join-alerts @app-db)))))

(deftest customer-search--success
  (testing "unmarks seaching"
    (is (some? (reset! app-db (db/set-customer-searching {} true))))
    (rf/dispatch-sync [:customer/search--success {:body [{:name "test customer"}]}])
    (is (not (db/customer-searching? @app-db))))

  (testing "sets search result in db"
    (let [matches [{:name "test customer"}]]
      (rf/dispatch-sync [:customer/search--success {:body matches}])
      (is (= matches (db/search-results @app-db))))))

(deftest customer-search--failed
  (testing "unmarks seaching"
    (is (some? (reset! app-db (db/set-customer-searching {} true))))
    (rf/dispatch-sync [:customer/search--failed "test error"])
    (is (not (db/customer-searching? @app-db))))

  (testing "sets error alert"
    (rf/dispatch-sync [:customer/search--failed "test error"])
    (is (= 1 (count (db/join-alerts @app-db))))
    (is (= :danger (-> (db/join-alerts @app-db) first :type)))))
