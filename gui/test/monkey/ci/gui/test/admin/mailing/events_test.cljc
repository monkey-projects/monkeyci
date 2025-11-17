(ns monkey.ci.gui.test.admin.mailing.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.admin.mailing.events :as sut]
            [monkey.ci.gui.admin.mailing.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :once f/admin-router)

(rf/clear-subscription-cache!)

(deftest cancel-edit
  (rf-test/run-test-sync
   (let [c (h/catch-fx :route/goto)]
     (is (some? (reset! app-db (-> {}
                                   (db/set-editing ::test-editing)
                                   (db/mark-saving)))))
     (rf/dispatch [::sut/cancel-edit])
     
     (testing "redirects to mailing overview"
       (is (= 1 (count @c)))
       (is (= "/emails" (first @c))))

     (testing "clears editing mailing"
       (is (nil? (db/get-editing @app-db))))

     (testing "unmarks saving"
       (is (not (db/saving? @app-db)))))))

(deftest save-mailing
  (rf-test/run-test-sync
   (let [m {:subject "test mailing"}
         c (h/catch-fx :martian.re-frame/request)]
     (h/initialize-martian {:admin-create-mailing {:error-code :no-error
                                                   :body {:id "new-mailing"}}})
     (is (some? (swap! app-db (fn [db]
                                (-> db
                                    (db/set-editing m)
                                    (db/set-editing-alerts [::test-alert]))))))
     (rf/dispatch [::sut/save-mailing])
     (testing "creates new mailing in backend"
       (is (= 1 (count @c)))
       (is (= :admin-create-mailing (-> @c first (nth 2)))))

     (testing "marks saving"
       (is (true? (db/saving? @app-db))))

     (testing "clears alerts"
      (is (empty? (db/get-editing-alerts @app-db)))))))

(deftest save-mailing--success
  (let [mailings [{:id "old-mailing"}]
        m {:id "new-mailing"}]
    (is (some? (reset! app-db (-> {}
                                  (db/set-mailings mailings)
                                  (db/mark-saving)))))

    (rf/dispatch-sync [::sut/save-mailing--success {:body m}])

    (testing "adds mailing to list"
      (let [r (db/get-mailings @app-db)]
        (is (= 2 (count r)))
        (is (= m (last r)))))
    
    (testing "unmarks saving"
      (is (not (db/saving? @app-db))))))

(deftest save-mailing--failure
  (is (some? (reset! app-db (db/mark-saving {}))))
  (rf/dispatch-sync [::sut/save-mailing--failure {:message "test error"}])
  (testing "sets alert"
    (is (= [:danger]
           (->> (db/get-editing-alerts @app-db)
                (map :type)))))

  (testing "unmarks saving"
    (is (not (db/saving? @app-db)))))

(deftest edit-prop-changed
  (testing "sets property in edit mailing"
    (rf/dispatch-sync [::sut/edit-prop-changed :subject "test subject"])
    (is (= "test subject"
           (:subject (db/get-editing @app-db))))))
