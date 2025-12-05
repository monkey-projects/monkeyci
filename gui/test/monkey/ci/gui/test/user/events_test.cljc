(ns monkey.ci.gui.test.user.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.user.db :as db]
            [monkey.ci.gui.user.events :as sut]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(rf/clear-subscription-cache!)

(use-fixtures :each f/reset-db)

(deftest general-load
  (let [uid "test-user"
        e (h/catch-fx :martian.re-frame/request)]
    (rf-test/run-test-sync
     (h/initialize-martian {:get-user-settings
                            {:status 200
                             :body {:user-id uid
                                    :receive-mailing true}
                             :error-code :no-error}})
     (is (some? (swap! app-db db/set-general-alerts [{:type :info :message "test alert"}])))
     (rf/dispatch [::sut/general-load uid])
     
     (testing "loads user settings from backend"
       (is (= 1 (count @e)))
       (is (= :get-user-settings (-> @e first (nth 2)))))

     (testing "passes user id in request"
       (is (= {:user-id uid}
              (-> @e first (nth 3)))))

     (testing "clears alerts"
       (is (empty? (db/get-general-alerts @app-db)))))))

(deftest general-load--success
  (let [settings {:user-id "test-user"
                  :receive-mailing true}]
    (rf/dispatch-sync [::sut/general-load--success {:body settings}])
    
    (testing "sets user settings in db"
      (is (= settings (db/get-user-settings @app-db))))))

(deftest general-load--failure
  (rf/dispatch-sync [::sut/general-load--failure {:message "test error"}])
  (testing "sets alert"
    (is (= [:danger]
           (map :type (db/get-general-alerts @app-db))))))

(deftest general-cancel
  (testing "resets general edit"
    (is (some? (reset! app-db (db/set-general-edit {} {:email "test"}))))
    (rf/dispatch-sync [::sut/general-cancel])
    (is (nil? (db/get-general-edit @app-db)))))

(deftest general-update
  (testing "updates property in general edit"
    (rf/dispatch-sync [::sut/general-update :email "updated-email"])
    (is (= "updated-email" (:email (db/get-general-edit @app-db))))))

(deftest general-save
  (let [uid "test-user"
        e (h/catch-fx :martian.re-frame/request)]
    (rf-test/run-test-sync
     (h/initialize-martian {:update-user-settings
                            {:status 200
                             :body {:user-id uid
                                    :receive-mailing true}
                             :error-code :no-error}})
     (rf/dispatch [::sut/general-save])
     
     (testing "updates settings in backend"
       (is (= 1 (count @e)))
       (is (= :update-user-settings (-> @e first (nth 2)))))

     (testing "marks saving"
       (is (true? (db/general-saving? @app-db)))))))

(deftest general-save--success
  (let [s {:user-id "test-user"}]
    (rf/dispatch-sync [::sut/general-save--success {:body s}])

    (testing "updates settings in db"
      (is (= s (db/get-user-settings @app-db))))

    (testing "sets success alert"
      (is (= [:success]
             (map :type (db/get-general-alerts @app-db)))))))

(deftest general-save--failure
  (rf/dispatch-sync [::sut/general-save--failure {:message "test error"}])
  (testing "sets alert"
    (is (= [:danger]
           (map :type (db/get-general-alerts @app-db))))))

