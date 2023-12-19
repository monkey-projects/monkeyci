(ns monkey.ci.gui.test.customer.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [martian.core :as martian]
            [martian.test :as mt]
            [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.customer.events :as sut]
            [monkey.ci.gui.customer.subs]
            [monkey.ci.gui.martian :as mm]
            [monkey.ci.gui.test.fixtures :as f]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

#_(rf/clear-subscription-cache!)

(defn catch-fx [fx]
  (let [inv (atom [])]
    (rf/reg-fx fx (fn [_ evt]
                    (swap! inv conj evt)))
    inv))

(defn initialize-martian [responses]
  (let [m (-> (martian/bootstrap mm/url mm/routes)
              (mt/respond-as "cljs-http")
              (mt/respond-with responses))]
    (rf/dispatch-sync [:martian.re-frame/init m])))

;; MAJOR CAVEAT!  Beware of test ordering, because sometimes async blocks are not executed.
;; Seems like only one async block per test is allowed, or something.
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
    (let [cust {:name "test customer"}]
      (rf-test/run-test-async
        (initialize-martian {:get-customer {:status 200
                                            :body cust
                                            :error-code :no-error}})
        (is (some? (:martian.re-frame/martian @app-db)))
        (rf/dispatch [:customer/load "test-customer"])
        (rf-test/wait-for
         [:customer/load--success
          :customer/load--failed
          evt]
         (is (= cust (:body (second evt))) "sends martian request")
         (is (= cust @(rf/subscribe [:customer/info])) "stored customer info"))))))

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
