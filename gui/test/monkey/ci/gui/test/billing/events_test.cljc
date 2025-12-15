(ns monkey.ci.gui.test.billing.events-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.billing.db :as db]
            [monkey.ci.gui.billing.events :as sut]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(rf/clear-subscription-cache!)

(use-fixtures :each f/reset-db)

(deftest load-invoicing
  (rf-test/run-test-sync
   (let [c (h/catch-fx :martian.re-frame/request)]
     (h/initialize-martian {:get-org-invoicing-settings
                            {:body {:currency "EUR"}
                             :error-code :no-error}})
     (is (some? (swap! app-db r/set-current {:parameters
                                             {:path
                                              {:org-id "test-org"}}})))
     (rf/dispatch [::sut/load-invoicing])

     (testing "loads invoicing settings from backend"
       (is (= 1 (count @c)))
       (is (= :get-org-invoicing-settings (-> @c first (nth 2))))))))

(deftest load-invoicing--success
  (rf/dispatch-sync [::sut/load-invoicing--success {:body {:vat-nr "VAT12342"
                                                           :address ["line 1" "line 2"]}}])

  (testing "sets invoicing settings in db"
    (is (= "VAT12342" (:vat-nr (db/get-invoicing-settings @app-db))))))

(deftest load-invoicing--failure
  (testing "sets error in alerts"
    (rf/dispatch-sync [::sut/load-invoicing--failure {:message "test error"}])
    (is (= [:danger] (->> (db/get-billing-alerts @app-db)
                          (map :type))))))

(deftest invoicing-settings-changed
  (testing "updates property"
    (rf/dispatch-sync [::sut/invoicing-settings-changed :vat-nr "updated"])
    (is (= "updated" (:vat-nr (db/get-invoicing-settings @app-db))))))

(deftest invoicing-address-changed
  (testing "updates address line at index"
    (is (some? (reset! app-db (db/set-invoicing-settings {} {:address ["first" "second" "third"]}))))
    (rf/dispatch-sync [::sut/invoicing-address-changed 1 "updated"])
    (is (= ["first" "updated" "third"]
           (:address (db/get-invoicing-settings @app-db))))))

(deftest save-invoicing
  (rf-test/run-test-sync
   (let [c (h/catch-fx :martian.re-frame/request)]
     (h/initialize-martian {:update-org-invoicing-settings
                            {:body {}
                             :error-code :no-error}})
     (is (some? (swap! app-db (fn [db]
                                (-> db
                                    (r/set-current {:parameters
                                                    {:path
                                                     {:org-id "test-org"}}})
                                    (db/set-invoicing-settings {:address ["line 1" "line 2"]})
                                    (db/set-billing-alerts [{:type :info :message "test alert"}]))))))
     (rf/dispatch [::sut/save-invoicing])

     (testing "saves invoicing settings in backend"
       (is (= 1 (count @c)))
       (is (= :update-org-invoicing-settings (-> @c first (nth 2)))))

     (testing "passes org id"
       (is (= "test-org" (-> @c first (nth 3) :org-id))))

     (testing "passes address lines"
       (is (= ["line 1" "line 2"] (-> @c first (nth 3) :settings :address))))

     (testing "clears alerts"
       (is (empty? (db/get-billing-alerts @app-db)))))))

(deftest save-invoicing--success
  (rf/dispatch-sync [::sut/save-invoicing--success {:body {:vat-nr "VAT12342"
                                                           :address ["line 1" "line 2"]}}])

  (testing "sets invoicing settings in db"
    (is (= "VAT12342" (:vat-nr (db/get-invoicing-settings @app-db)))))

  (testing "sets success alert"
    (is (= [:success] (->> (db/get-billing-alerts @app-db)
                           (map :type))))))

(deftest save-invoicing--failure
  (testing "sets error in alerts"
    (rf/dispatch-sync [::sut/save-invoicing--failure {:message "test error"}])
    (is (= [:danger] (->> (db/get-billing-alerts @app-db)
                          (map :type))))))
