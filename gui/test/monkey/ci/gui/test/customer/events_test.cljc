(ns monkey.ci.gui.test.customer.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.customer.events :as sut]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.login.events]
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

(deftest customer-load-github-repos
  (testing "invokes repos and orgs url from github user"
    (let [e (h/catch-fx :http-xhrio)]
      (rf-test/run-test-sync
       (is (some? (reset! app-db (ldb/set-github-user {} {:organizations-url "http://test-orgs"}))))
       (rf/dispatch [:customer/load-github-repos])
       (is (= 2 (count @e)))
       (is (= #{"https://api.github.com/user/repos" "http://test-orgs"}
              (set (map :uri @e)))))))

  (testing "sets info alert"
    (let [_ (h/catch-fx :http-xhrio)]
      (rf-test/run-test-sync
       (is (some? (reset! app-db (ldb/set-github-user {} {:repos-url "http://test-repos"}))))
       (rf/dispatch [:customer/load-github-repos])
       (let [a (db/repo-alerts @app-db)]
         (is (= 1 (count a)))
         (is (= :info (-> a first :type))))))))

(deftest customer-load-github-repos--success
  (testing "sets repos in db"
    (rf/dispatch-sync [:customer/load-github-repos--success [{:id "test-repo"}]])
    (is (= 1 (count (db/github-repos @app-db)))))

  (testing "sets success alert"
    (rf/dispatch-sync [:customer/load-github-repos--success [{:id "test-repo"}]])
      (let [a (db/repo-alerts @app-db)]
        (is (= 1 (count a)))
        (is (= :success (-> a first :type))))))

(deftest load-orgs
  (testing "invokes orgs url from github user"
    (let [e (h/catch-fx :http-xhrio)]
      (is (some? (reset! app-db (ldb/set-github-user {} {:organizations-url "http://test-orgs"}))))
      (rf/dispatch-sync [::sut/load-orgs])
      (is (= 1 (count @e)))
      (is (= "http://test-orgs" (-> @e first :uri))))))

(deftest load-orgs--success
  (testing "fetches repos for each org"
    (let [e (h/catch-fx :http-xhrio)]
      (rf-test/run-test-sync
       (rf/dispatch [::sut/load-orgs--success [{:repos-url "http://test-repos/org-1"}
                                               {:repos-url "http://test-repos/org-2"}]])
       (is (= 2 (count @e)))
       (is (= ["http://test-repos/org-1"
               "http://test-repos/org-2"]
              (map :uri @e)))))))

(deftest repo-watch
  (testing "creates new repo in backend"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:create-repo {:status 204
                                            :body {:id "test-repo"}
                                            :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:repo/watch {:id "github-id"
                                  :private false
                                  :name "test-repo"
                                  :clone-url "http://test-url"}])
       (is (= 1 (count @c)))
       (is (= :create-repo (-> @c first (nth 2))))))))

(deftest repo-watch--success
  (testing "adds repo to customer"
    (is (some? (reset! app-db (db/set-customer {} {:repos []}))))
    (rf/dispatch-sync [:repo/watch--success {:body {:id "test-repo"}}])
    (is (= {:repos [{:id "test-repo"}]}
           (db/customer @app-db)))))

(deftest repo-watch--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:repo/watch--failed {:message "test error"}])
    (let [a (db/repo-alerts @app-db)]
      (is (= 1 (count a)))
      (is (= :danger (:type (first a)))))))

(deftest repo-unwatch
  (testing "disables repo in backend"))
