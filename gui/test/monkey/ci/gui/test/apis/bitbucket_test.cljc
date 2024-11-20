(ns monkey.ci.gui.test.apis.bitbucket-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.apis.bitbucket :as sut]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest bitbucket-load-repos
  (testing "clears repos"
    (is (some? (reset! app-db (sut/set-repos {} [::original-repos]))))
    (rf/dispatch-sync [:bitbucket/load-repos])
    (is (empty? (sut/repos @app-db)))))

(deftest load-workspaces
  (testing "invokes workspaces url on bitbucket api"
    (let [e (h/catch-fx :http-xhrio)]
      (rf/dispatch-sync [::sut/load-workspaces])
      (is (= 1 (count @e)))
      (is (= "https://api.bitbucket.org/2.0/user/permissions/workspaces"
             (-> @e first :uri))))))

(deftest load-workspaces--success
  (testing "fetches repos for each workspace"
    (let [e (h/catch-fx :http-xhrio)]
      (rf-test/run-test-sync
       (rf/dispatch [::sut/load-workspaces--success {:values
                                                     [{:workspace {:uuid "test-id-1"}}
                                                      {:workspace {:uuid "test-id-2"}}]}])
       (is (= 2 (count @e)))
       (is (= ["https://api.bitbucket.org/2.0/repositories/test-id-1"
               "https://api.bitbucket.org/2.0/repositories/test-id-2"]
              (map :uri @e)))))))

(deftest load-workspaces--failed
  (testing "redirect to login on 401 error"
    (rf-test/run-test-sync
     (let [e (h/catch-fx :route/goto)]
       (rf/dispatch [::sut/load-workspaces--failed {:status 401}])
       (is (= ["/login"] @e))))))

(deftest load-repos
  (testing "loads workspace repos from bitbucket"
    (let [e (h/catch-fx :http-xhrio)]
      (rf/dispatch-sync [::sut/load-repos "test-ws-id"])
      (is (= 1 (count @e)))
      (is (= "https://api.bitbucket.org/2.0/repositories/test-ws-id"
             (-> @e first :uri))))))

(deftest load-repos--success
  (testing "adds repos to db"
    (is (empty? (sut/repos @app-db)))
    (rf/dispatch-sync [::sut/load-repos--success ::test-ws {:values [::repo-1 ::repo-2]}])
    (is (= [::repo-1 ::repo-2] (sut/repos @app-db)))
    (rf/dispatch-sync [::sut/load-repos--success ::test-ws {:values [::repo-3]}])
    (is (= [::repo-1 ::repo-2 ::repo-3] (sut/repos @app-db)))))

(deftest load-repos--failed
  (testing "redirect to login on 401 error"
    (rf-test/run-test-sync
     (let [e (h/catch-fx :route/goto)]
       (rf/dispatch [::sut/load-repos--failed ::test-ws {:status 401}])
       (is (= ["/login"] @e)))))

  (testing "sets alert"
    (rf/dispatch-sync [::sut/load-repos--failed ::test-ws "test error"])
    (is (= :danger (-> @app-db (sut/alerts) first :type)))))

(deftest alerts
  (let [s (rf/subscribe [:bitbucket/alerts])]
    (testing "exists"
      (is (some? s)))

    (testing "returns alerts from db"
      (let [a [{:type :info
                :message "Test alert"}]]
        (is (map? (reset! app-db (sut/set-alerts {} a))))
        (is (= a @s))))))

(deftest repos-sub
  (h/verify-sub [:bitbucket/repos]
                #(sut/set-repos % [::test-repos])
                [::test-repos]
                nil))
