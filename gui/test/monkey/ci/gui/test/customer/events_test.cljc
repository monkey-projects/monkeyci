(ns monkey.ci.gui.test.customer.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.customer.events :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

;; Not using run-test-async cause it tends to block and there are issues when
;; there are multiple async blocks in one test.
(deftest customer-load
  (testing "sets state to loading"
    (rf-test/run-test-sync
     (rf/dispatch [:customer/load "load-customer"])
     (is (true? (db/loading? @app-db)))))

  (testing "sets alert"
    (rf-test/run-test-sync
     (rf/dispatch [:customer/load "fail-customer"])
     (is (= 1 (count (db/alerts @app-db))))))

  (testing "sends request to api and sets customer"
    (rf-test/run-test-sync
     (let [cust {:name "test customer"}
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-customer {:status 200
                                             :body cust
                                             :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:customer/load "test-customer"])
       (is (= 1 (count @c)))
       (is (= :get-customer (-> @c first (nth 2))))))))

(deftest customer-load--success
  (testing "unmarks loading"
    (is (map? (reset! app-db (db/set-loading {}))))
    (rf/dispatch-sync [:customer/load--success "test-customer"])
    (is (not (db/loading? @app-db)))))

(deftest customer-load--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:customer/load--failed "test-cust" "test error"])
    (let [[err] (db/alerts @app-db)]
      (is (= :danger (:type err)))
      (is (re-matches #".*test-cust.*" (:message err)))))

  (testing "unmarks loading"
    (is (map? (reset! app-db (db/set-loading {}))))
    (rf/dispatch-sync [:customer/load--failed "test-id" "test-customer"])
    (is (not (db/loading? @app-db)))))
