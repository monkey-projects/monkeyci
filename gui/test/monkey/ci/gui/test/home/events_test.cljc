(ns monkey.ci.gui.test.home.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.home.events :as sut]
            [monkey.ci.gui.home.db :as db]
            [monkey.ci.gui.login.db :as ldb]
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
       (is (= :get-user-customers (-> @c first (nth 2))))))))

(deftest user-load-customers--success
  (testing "sets customers in db"
    (rf/dispatch-sync [:user/load-customers--success {:body ::customers}])
    (is (= ::customers (db/get-customers @app-db)))))

(deftest user-load-customers--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:user/load-customers--failed {:message "test error"}])
    (is (= :danger (-> (db/get-alerts @app-db)
                       first
                       :type)))))

(deftest customer-join-init
  (testing "clears search results"
    (is (some? (reset! app-db (db/set-search-results {} ::results))))
    (rf/dispatch-sync [:customer/join-init])
    (is (nil? (db/search-results @app-db))))

  (testing "clears join requests"
    (is (some? (reset! app-db (db/set-join-requests {} ::results))))
    (rf/dispatch-sync [:customer/join-init])
    (is (nil? (db/join-requests @app-db))))

  (testing "clears joining flags"
    (is (some? (reset! app-db (db/mark-customer-joining {} "test-cust"))))
    (rf/dispatch-sync [:customer/join-init])
    (is (not (db/customer-joining? @app-db "test-cust")))))

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

(deftest join-request-load
  (testing "sends request to api using current user id"
    (rf-test/run-test-sync
     (let [jr [{:customer-id "test-cust"}]
           c (h/catch-fx :martian.re-frame/request)]
       (is (some? (reset! app-db (ldb/set-user {} {:id "test-user"}))))
       (h/initialize-martian {:get-user-join-requests {:status 200
                                                       :body jr
                                                       :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:join-request/load])
       (is (= 1 (count @c)))
       (is (= "test-user" (-> @c first (nth 3) :user-id)))))))

(deftest join-request-load--success
  (testing "sets join requests in db"
    (rf/dispatch-sync [:join-request/load--success {:body ::test-requests}])
    (is (= ::test-requests (db/join-requests @app-db)))))

(deftest join-request-load--failed
  (testing "clears join requests"
    (is (some? (reset! app-db (db/set-join-requests {} ::requests))))
    (rf/dispatch-sync [:join-request/load--failed "test error"])
    (is (empty? (db/join-requests @app-db))))

  (testing "sets error alert"
    (rf/dispatch-sync [:join-request/load--failed "test error"])
    (is (= 1 (count (db/join-alerts @app-db))))
    (is (= :danger (-> (db/join-alerts @app-db) first :type)))))

(deftest customer-join
  (rf-test/run-test-sync
   (let [cust-id "test-customer"
         c (h/catch-fx :martian.re-frame/request)]
     (is (some? (reset! app-db (ldb/set-user {} {:id "test-user"}))))
     (h/initialize-martian {:create-user-join-requests {:status 200
                                                        :body {:customer-id cust-id}
                                                        :error-code :no-error}})
     (is (some? (:martian.re-frame/martian @app-db)))
     (rf/dispatch [:customer/join cust-id])
     (testing "sends request to backend"
       (is (= 1 (count @c)))
       (is (= "test-user" (-> @c first (nth 3) :user-id))))
     
     (testing "marks customer joining"
       (is (db/customer-joining? @app-db cust-id))))))

(deftest customer-join--success
  (testing "unmarks customer joining"
    (let [cust-id "test-id"]
      (is (some? (reset! app-db (db/mark-customer-joining {} cust-id))))
      (rf/dispatch-sync [:customer/join--success {:body {:customer-id cust-id}}])
      (is (not (db/customer-joining? @app-db cust-id)))))
  
  (testing "updates join request list"
    (let [jr {:id "test-id"
              :customer-id "test-cust"
              :user-id "test-user"}]
      (is (empty? (reset! app-db {})))
      (rf/dispatch-sync [:customer/join--success {:body jr}])
      (is (= [jr] (db/join-requests @app-db))))))

(deftest customer-join--failed
  (testing "unmarks customer joining"
    (let [cust-id "test-cust"]
      (is (some? (reset! app-db (db/mark-customer-joining {} cust-id))))
      (rf/dispatch-sync [:customer/join--failed cust-id "test error"])
      (is (not (db/customer-joining? @app-db cust-id)))))

  (testing "sets error alert"
    (rf/dispatch-sync [:customer/join--failed "test-cust" "test error"])
    (is (= 1 (count (db/join-alerts @app-db))))
    (is (= :danger (-> (db/join-alerts @app-db) first :type)))))

(deftest join-request-delete
  (testing "sends delete request to backend")
  (testing "marks deleting"))

(deftest join-request-delete--success
  (testing "removes join request from list")
  (testing "unmarks deleting"))

(deftest join-request-delete--failed
  (testing "sets error alert")
  (testing "unmarks deleting"))
