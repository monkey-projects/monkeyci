(ns monkey.ci.gui.test.login.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.login.events :as sut]
            [monkey.ci.gui.login.db :as db]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each
  f/reset-db)

(deftest login-and-redirect
  (testing "sets redirect route in local storage"
    (h/catch-fx :route/goto)
    (let [c (h/catch-fx :local-storage)]
      (rf-test/run-test-sync
       (is (some? (reset! app-db {r/current {:path "/redirect/path"}})))
       (rf/dispatch [:login/login-and-redirect])
       (is (= "/redirect/path" (-> @c first second :redirect-to))))))

  (testing "changes route to login"
    (let [r (h/catch-fx :route/goto)]
      (rf-test/run-test-sync
       (rf/dispatch [:login/login-and-redirect])
       (is (= [(r/path-for :page/login)] @r))))))

(deftest login-submit
  (testing "updates state"
    (rf/dispatch-sync [:login/submit])
    (is (true? (db/submitting? @app-db)))))

(deftest login-authenticated
  (testing "sets user in state"
    (let [user {:username "testuser"}]
      (rf/dispatch-sync [:login/authenticated user])
      (is (= user (db/user @app-db)))))

  (testing "unsets submitting state"
    (is (map? (reset! app-db (db/set-submitting {}))))
    (rf/dispatch-sync [:login/authenticated "some user"])
    (is (false? (db/submitting? @app-db)))))

(deftest github-code-received
  (testing "sends exchange request to backend"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:github-login {:status 200
                                             :body "ok"
                                             :error-code :no-error}})
       (rf/dispatch [:login/github-code-received "test-code"])
       (is (= 1 (count @c))))))

  (testing "clears alerts in db"
    (is (map? (reset! app-db (db/set-alerts {} [{:type :info}]))))
    (rf/dispatch-sync [:login/github-code-received "test-code"])
    (is (empty? (db/alerts @app-db))))

  (testing "clears user in db"
    (is (map? (reset! app-db (db/set-user {} ::test-user))))
    (rf/dispatch-sync [:login/github-code-received "test-code"])
    (is (nil? (db/user @app-db)))))

(deftest github-login--success
  ;; Failsafe
  (h/catch-fx :route/goto)
  
  (testing "sets user in db"
    (rf/dispatch-sync [:login/github-login--success {:body {:id ::test-user}}])
    (is (= {:id ::test-user} (db/user @app-db))))

  (testing "sets token in db"
    (rf/dispatch-sync [:login/github-login--success {:body {:token "test-token"}}])
    (is (= "test-token" (db/token @app-db))))

  (testing "redirects to root page if multiple customers"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :route/goto)]
       (rf/dispatch [:login/github-login--success {:body {:customers ["cust-1" "cust-2"]}}])
       (is (= "/" (first @c))))))

  (testing "redirects to customer page if only one customer"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :route/goto)]
       (rf/dispatch [:login/github-login--success {:body {:customers ["test-cust"]}}])
       (is (= "/c/test-cust" (first @c))))))

  (testing "redirects to redirect page if set"
    (rf-test/run-test-sync
     (rf/reg-cofx
      :local-storage
      (fn [cofx]
        (assoc cofx :local-storage {:redirect-to "/c/test-cust"})))
     (let [c (h/catch-fx :route/goto)]
       (rf/dispatch [:login/github-login--success {:body {}}])
       (is (= "/c/test-cust" (first @c)))))))

(deftest github-login--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:login/github-login--failed {:message "test error"}])
    (is (= :danger (-> (db/alerts @app-db)
                       (first)
                       :type)))))

(deftest load-github-config
  (testing "sends request to backend to fetch config"
    (rf-test/run-test-sync
      (let [c (h/catch-fx :martian.re-frame/request)]
        (h/initialize-martian {:get-github-config {:status 200
                                                   :body "ok"
                                                   :error-code :no-error}})
        (rf/dispatch [:login/load-github-config])
        (is (= 1 (count @c)))))))

(deftest load-github-config--success
  (testing "sets github config in db"
    (rf/dispatch-sync [:login/load-github-config--success {:body ::test-config}])
    (is (= ::test-config (db/github-config @app-db)))))
