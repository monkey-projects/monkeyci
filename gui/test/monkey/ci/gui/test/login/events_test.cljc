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
  (h/catch-fx :route/goto)
  (testing "sets redirect route in local storage"
    (let [c (h/catch-fx :local-storage)]
      (rf-test/run-test-sync
       (is (some? (reset! app-db {r/current {:path "/redirect/path"}})))
       (rf/dispatch [:login/login-and-redirect])
       (is (= "/redirect/path" (-> @c first second :redirect-to))))))

  (testing "does not set if route is public"
    (let [c (h/catch-fx :local-storage)]
      (rf-test/run-test-sync
       (is (some? (reset! app-db {r/current {:path "/github/callback"
                                             :data {:name :page/github-callback}}})))
       (rf/dispatch [:login/login-and-redirect])
       (is (nil? (-> @c first second :redirect-to))))))

  (testing "changes route to login"
    (let [r (h/catch-fx :route/goto)]
      (rf-test/run-test-sync
       (rf/dispatch [:login/login-and-redirect])
       (is (= [(r/path-for :page/login)] @r))))))

(deftest login-submit
  (testing "updates state"
    (rf/dispatch-sync [:login/submit])
    (is (true? (db/submitting? @app-db)))))

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
  ;; Safety
  (h/catch-fx :http-xhrio)
  
  (testing "sets user in db"
    (rf/dispatch-sync [:login/github-login--success {:body {:id ::test-user}}])
    (is (= {:id ::test-user} (db/user @app-db))))

  (testing "sets token in db"
    (rf/dispatch-sync [:login/github-login--success {:body {:token "test-token"}}])
    (is (= "test-token" (db/token @app-db))))

  (testing "sets github token in db"
    (rf/dispatch-sync [:login/github-login--success {:body {:github-token "test-token"}}])
    (is (= "test-token" (db/github-token @app-db))))

  (testing "fetches github user details"
    (let [e (h/catch-fx :http-xhrio)]
      (rf/dispatch-sync [:login/github-login--success {:body {:token "test-token"
                                                              :github-token "github-token"}}])
      (is (= 1 (count @e)))
      (is (= {:method :get
              :uri "https://api.github.com/user"}
             (select-keys (first @e) [:method :uri])))))

  (testing "saves tokens to local storage"
    (let [e (h/catch-fx :local-storage)]
      (rf/dispatch-sync [:login/github-login--success {:body {:token "test-token"
                                                              :github-token "test-github"}}])
      (is (= [["login-tokens" {:token "test-token"
                               :github-token "test-github"}]]
             @e)))))

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

(deftest github-load-user--success
  ;; Failsafe
  (h/catch-fx :route/goto)

  (testing "sets github user in db"
    (rf/dispatch-sync [:github/load-user--success ::github-user])
    (is (= ::github-user (db/github-user @app-db))))
  
  (testing "redirects to root page if multiple orgs"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :route/goto)]
       (reset! app-db (db/set-user {} {:orgs ["org-1" "org-2"]}))
       (rf/dispatch [:github/load-user--success {}])
       (is (= "/" (first @c))))))

  (testing "redirects to org page if only one org"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :route/goto)]
       (reset! app-db (db/set-user {} {:orgs ["test-org"]}))
       (rf/dispatch [:github/load-user--success {}])
       (is (= "/o/test-org" (first @c))))))

  (testing "redirects to redirect page if set"
    (rf-test/run-test-sync
     (rf/reg-cofx
      :local-storage
      (fn [cofx]
        (assoc cofx :local-storage {:redirect-to "/o/test-org"})))
     (let [c (h/catch-fx :route/goto)]
       (rf/dispatch [:github/load-user--success {}])
       (is (= "/o/test-org" (first @c))))))

  (testing "ignores redirect page if `/`"
    (rf-test/run-test-sync
     (rf/reg-cofx
      :local-storage
      (fn [cofx]
        (assoc cofx :local-storage {:redirect-to "/"})))
     (let [c (h/catch-fx :route/goto)]
       (reset! app-db (db/set-user {} {:orgs ["test-org"]}))
       (rf/dispatch [:github/load-user--success {}])
       (is (= "/o/test-org" (first @c)))))))

(deftest github-load-user--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:github/load-user--failed {:response "test error"}])
    (let [a (db/alerts @app-db)]
      (is (= :danger (-> a first :type)))
      (is (string? (-> a first :message)))))

  (testing "on 401 error, refreshes token and retries"
    (rf-test/run-test-sync
     (rf/reg-cofx
      :local-storage
      (fn [cofx]
        (assoc cofx :local-storage {:refresh-token "test-refresh-token"})))
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:github-refresh {:status 200
                                               :body {:github-token "github-token"}
                                               :error-code :no-error}})
       (is (some? (swap! app-db db/set-provider-auth {:provider :github})))
       (rf/dispatch [:github/load-user--failed {:status 401}])
       (is (= 1 (count @c)) "expected refresh token request")
       (is (= :github-refresh (-> @c first (nth 2))))
       (is (= [:github/load-user] (-> @c first (nth 4) second)))))))

(deftest load-bitbucket-config
  (testing "sends request to backend to fetch config"
    (rf-test/run-test-sync
      (let [c (h/catch-fx :martian.re-frame/request)]
        (h/initialize-martian {:get-bitbucket-config {:status 200
                                                      :body "ok"
                                                      :error-code :no-error}})
        (rf/dispatch [:login/load-bitbucket-config])
        (is (= 1 (count @c)))))))

(deftest load-bitbucket-config--success
  (testing "sets bitbucket config in db"
    (rf/dispatch-sync [:login/load-bitbucket-config--success {:body ::test-config}])
    (is (= ::test-config (db/bitbucket-config @app-db)))))

(deftest bitbucket-code-received
  (testing "sends exchange request to backend"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:bitbucket-login {:status 200
                                                :body "ok"
                                                :error-code :no-error}})
       (rf/dispatch [:login/bitbucket-code-received "test-code"])
       (is (= 1 (count @c))))))

  (testing "clears alerts in db"
    (is (map? (reset! app-db (db/set-alerts {} [{:type :info}]))))
    (rf/dispatch-sync [:login/bitbucket-code-received "test-code"])
    (is (empty? (db/alerts @app-db))))

  (testing "clears user in db"
    (is (map? (reset! app-db (db/set-user {} ::test-user))))
    (rf/dispatch-sync [:login/bitbucket-code-received "test-code"])
    (is (nil? (db/user @app-db)))))

(deftest bitbucket-login--success
  ;; Safety
  (h/catch-fx :http-xhrio)
  
  (testing "sets user in db"
    (rf/dispatch-sync [:login/bitbucket-login--success {:body {:id ::test-user}}])
    (is (= {:id ::test-user} (db/user @app-db))))

  (testing "sets token in db"
    (rf/dispatch-sync [:login/bitbucket-login--success {:body {:token "test-token"}}])
    (is (= "test-token" (db/token @app-db))))

  (testing "sets bitbucket token in db"
    (rf/dispatch-sync [:login/bitbucket-login--success {:body {:bitbucket-token "test-token"}}])
    (is (= "test-token" (db/bitbucket-token @app-db))))

  (testing "fetches bitbucket user details"
    (let [e (h/catch-fx :http-xhrio)]
      (rf/dispatch-sync [:login/bitbucket-login--success {:body
                                                          {:token "test-token"
                                                           :bitbucket-token "test-bb-token"}}])
      (is (= 1 (count @e)))
      (is (= {:method :get
              :uri "https://api.bitbucket.org/2.0/user"}
             (select-keys (first @e) [:method :uri])))))

  (testing "saves tokens to local storage"
    (let [e (h/catch-fx :local-storage)]
      (rf/dispatch-sync [:login/bitbucket-login--success {:body {:token "test-token"
                                                                 :bitbucket-token "test-bitbucket"}}])
      (is (= [["login-tokens" {:token "test-token"
                               :bitbucket-token "test-bitbucket"}]]
             @e)))))

(deftest bitbucket-login--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:login/bitbucket-login--failed {:message "test error"}])
    (is (= :danger (-> (db/alerts @app-db)
                       (first)
                       :type)))))

(deftest bitbucket-load-user--success
  ;; Failsafe
  (h/catch-fx :route/goto)

  (testing "sets bitbucket user in db"
    (rf/dispatch-sync [:bitbucket/load-user--success ::bitbucket-user])
    (is (= ::bitbucket-user (db/bitbucket-user @app-db))))
  
  (testing "redirects to root page if multiple orgs"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :route/goto)]
       (reset! app-db (db/set-user {} {:orgs ["org-1" "org-2"]}))
       (rf/dispatch [:bitbucket/load-user--success {}])
       (is (= "/" (first @c))))))

  (testing "redirects to org page if only one org"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :route/goto)]
       (reset! app-db (db/set-user {} {:orgs ["test-org"]}))
       (rf/dispatch [:bitbucket/load-user--success {}])
       (is (= "/o/test-org" (first @c))))))

  (testing "redirects to redirect page if set"
    (rf-test/run-test-sync
     (rf/reg-cofx
      :local-storage
      (fn [cofx]
        (assoc cofx :local-storage {:redirect-to "/o/test-org"})))
     (let [c (h/catch-fx :route/goto)]
       (rf/dispatch [:bitbucket/load-user--success {}])
       (is (= "/o/test-org" (first @c))))))

  (testing "ignores redirect page if `/`"
    (rf-test/run-test-sync
     (rf/reg-cofx
      :local-storage
      (fn [cofx]
        (assoc cofx :local-storage {:redirect-to "/"})))
     (let [c (h/catch-fx :route/goto)]
       (reset! app-db (db/set-user {} {:orgs ["test-org"]}))
       (rf/dispatch [:bitbucket/load-user--success {}])
       (is (= "/o/test-org" (first @c)))))))

(deftest bitbucket-load-user--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:bitbucket/load-user--failed {:response "test error"}])
    (let [a (db/alerts @app-db)]
      (is (= :danger (-> a first :type)))
      (is (string? (-> a first :message))))))

(deftest load-codeberg-config
  (testing "sends request to backend to fetch config"
    (rf-test/run-test-sync
      (let [c (h/catch-fx :martian.re-frame/request)]
        (h/initialize-martian {:get-codeberg-config {:status 200
                                                     :body "ok"
                                                     :error-code :no-error}})
        (rf/dispatch [:login/load-codeberg-config])
        (is (= 1 (count @c)))))))

(deftest load-codeberg-config--success
  (testing "sets codeberg config in db"
    (rf/dispatch-sync [:login/load-codeberg-config--success {:body ::test-config}])
    (is (= ::test-config (db/codeberg-config @app-db)))))

(deftest codeberg-code-received
  (testing "sends exchange request to backend"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:codeberg-login {:status 200
                                               :body "ok"
                                               :error-code :no-error}})
       (rf/dispatch [:login/codeberg-code-received "test-code"])
       (is (= 1 (count @c))))))

  (testing "clears alerts in db"
    (is (map? (reset! app-db (db/set-alerts {} [{:type :info}]))))
    (rf/dispatch-sync [:login/codeberg-code-received "test-code"])
    (is (empty? (db/alerts @app-db))))

  (testing "clears user in db"
    (is (map? (reset! app-db (db/set-user {} ::test-user))))
    (rf/dispatch-sync [:login/codeberg-code-received "test-code"])
    (is (nil? (db/user @app-db)))))

(deftest codeberg-login--success
  ;; Safety
  (h/catch-fx :http-xhrio)
  
  (testing "sets user in db"
    (rf/dispatch-sync [:login/codeberg-login--success {:body {:id ::test-user}}])
    (is (= {:id ::test-user} (db/user @app-db))))

  (testing "sets token in db"
    (rf/dispatch-sync [:login/codeberg-login--success {:body {:token "test-token"}}])
    (is (= "test-token" (db/token @app-db))))

  (testing "sets codeberg token in db"
    (rf/dispatch-sync [:login/codeberg-login--success {:body {:codeberg-token "test-token"}}])
    (is (= "test-token" (db/codeberg-token @app-db))))

  (testing "fetches codeberg user details"
    (let [e (h/catch-fx :http-xhrio)]
      (rf/dispatch-sync [:login/codeberg-login--success {:body {:token "test-token"
                                                                :codeberg-token "codeberg-token"}}])
      (is (= 1 (count @e)))
      (is (= {:method :get
              :uri "https://codeberg.org/api/v1/user"}
             (select-keys (first @e) [:method :uri])))))

  (testing "saves tokens to local storage"
    (let [e (h/catch-fx :local-storage)]
      (rf/dispatch-sync [:login/codeberg-login--success {:body {:token "test-token"
                                                                :codeberg-token "test-codeberg"}}])
      (is (= [["login-tokens" {:token "test-token"
                               :codeberg-token "test-codeberg"}]]
             @e)))))

(deftest codeberg-login--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:login/codeberg-login--failed {:message "test error"}])
    (is (= :danger (-> (db/alerts @app-db)
                       (first)
                       :type)))))

(deftest codeberg-load-user--success
  ;; Failsafe
  (h/catch-fx :route/goto)

  (testing "sets codeberg user in db"
    (rf/dispatch-sync [:codeberg/load-user--success ::codeberg-user])
    (is (= ::codeberg-user (db/codeberg-user @app-db))))
  
  (testing "redirects to root page if multiple orgs"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :route/goto)]
       (reset! app-db (db/set-user {} {:orgs ["org-1" "org-2"]}))
       (rf/dispatch [:codeberg/load-user--success {}])
       (is (= "/" (first @c))))))

  (testing "redirects to org page if only one org"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :route/goto)]
       (reset! app-db (db/set-user {} {:orgs ["test-org"]}))
       (rf/dispatch [:codeberg/load-user--success {}])
       (is (= "/o/test-org" (first @c))))))

  (testing "redirects to redirect page if set"
    (rf-test/run-test-sync
     (rf/reg-cofx
      :local-storage
      (fn [cofx]
        (assoc cofx :local-storage {:redirect-to "/o/test-org"})))
     (let [c (h/catch-fx :route/goto)]
       (rf/dispatch [:codeberg/load-user--success {}])
       (is (= "/o/test-org" (first @c))))))

  (testing "ignores redirect page if `/`"
    (rf-test/run-test-sync
     (rf/reg-cofx
      :local-storage
      (fn [cofx]
        (assoc cofx :local-storage {:redirect-to "/"})))
     (let [c (h/catch-fx :route/goto)]
       (reset! app-db (db/set-user {} {:orgs ["test-org"]}))
       (rf/dispatch [:codeberg/load-user--success {}])
       (is (= "/o/test-org" (first @c)))))))

(deftest codeberg-load-user--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:codeberg/load-user--failed {:response "test error"}])
    (let [a (db/alerts @app-db)]
      (is (= :danger (-> a first :type)))
      (is (string? (-> a first :message)))))

  (testing "on 401 error, refreshes token and retries"
    (rf-test/run-test-sync
     (rf/reg-cofx
      :local-storage
      (fn [cofx]
        (assoc cofx :local-storage {:refresh-token "test-refresh-token"})))
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:codeberg-refresh {:status 200
                                                 :body {:codeberg-token "codeberg-token"}
                                                 :error-code :no-error}})
       (is (some? (swap! app-db db/set-provider-auth {:provider :codeberg})))
       (rf/dispatch [:codeberg/load-user--failed {:status 401}])
       (is (= 1 (count @c)) "expected refresh token request")
       (is (= :codeberg-refresh (-> @c first (nth 2))))
       (is (= [:codeberg/load-user] (-> @c first (nth 4) second)))))))

(deftest login-log-off
  (h/catch-fx :route/goto)
  
  (testing "clears user and token from db"
    (is (some? (reset! app-db (-> {}
                                  (db/set-user {:id "test-user"})
                                  (db/set-token "test-token")))))
    (rf/dispatch-sync [:login/log-off])
    (is (nil? (db/user @app-db)))
    (is (nil? (db/token @app-db))))

  (testing "redirect to login page"
    (let [r (h/catch-fx :route/goto)]
      (rf-test/run-test-sync
       (rf/dispatch [:login/log-off])
       (is (= [(r/path-for :page/login)] @r)))))

  (testing "removes tokens from local storage"
    (let [r (h/catch-fx :local-storage)]
      (rf/dispatch-sync [:login/log-off])
      (is (= [[sut/storage-token-id nil]]
             @r)))))
