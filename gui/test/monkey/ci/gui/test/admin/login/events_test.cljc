(ns monkey.ci.gui.test.admin.login.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.admin.login.events :as sut]
            [monkey.ci.gui.admin.login.db :as db]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest login-submit
  (testing "sends login request to backend"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:admin-login {:status 200
                                            :body "ok"
                                            :error-code :no-error}})
       (rf/dispatch [::sut/submit {:username "test-user"
                                   :password "test-pass"}])
       (is (= 1 (count @c))))))

  (testing "marks submitting"
    (rf/dispatch-sync [::sut/submit])
    (is (db/submitting? @app-db)))

  (testing "clears alerts"
    (is (some? (reset! app-db (ldb/set-alerts {} [{:type :info :message "test message"}]))))
    (rf/dispatch-sync [::sut/submit])
    (is (empty? (ldb/alerts @app-db)))))

(deftest login-submit--success
  (rf-test/run-test-sync
   (let [c (h/catch-fx :route/goto)]
     (rf/dispatch [::sut/submit--success {:body {:type-id "test-user"
                                                 :token "test-token"}}])

     (testing "sets user"
       (is (= "test-user"
              (-> (ldb/user @app-db)
                  :name))))

     (testing "sets token"
       (is (= "test-token"
              (ldb/token @app-db))))

     (testing "redirects to root page"
       (is (= 1 (count @c)))
       (is (nil? (first @c)))))))

(deftest login-submit--failed
  (testing "sets error alert"
    (rf/dispatch-sync [::sut/submit--failed "test error"])
    (is (= [:danger]
           (->> (ldb/alerts @app-db)
                (map :type))))))
