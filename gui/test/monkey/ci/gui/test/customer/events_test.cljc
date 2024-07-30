(ns monkey.ci.gui.test.customer.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.customer.events :as sut]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.login.events]
            [monkey.ci.gui.routing :as r]
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

(deftest customer-maybe-load
  (testing "loads customer if not in db"
    (rf-test/run-test-sync
     (let [cust {:id "test-cust"
                 :name "Test customer"}
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-customer {:status 200
                                             :body cust
                                             :error-code :no-error}})
       (rf/dispatch [:customer/maybe-load (:id cust)])
       (is (= 1 (count @c))))))

  (testing "does not load if already in db"
    (rf-test/run-test-sync
     (let [cust {:id "test-cust"
                 :name "Test customer"}
           c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (db/set-customer {} cust))
       (h/initialize-martian {:get-customer {:status 200
                                             :body cust
                                             :error-code :no-error}})
       (rf/dispatch [:customer/maybe-load (:id cust)])
       (is (empty? @c)))))

  (testing "loads if id differs from id in db"
    (rf-test/run-test-sync
     (let [cust {:id "test-cust"
                 :name "Test customer"}
           c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (db/set-customer {} {:id "other-cust"}))
       (h/initialize-martian {:get-customer {:status 200
                                             :body cust
                                             :error-code :no-error}})
       (rf/dispatch [:customer/maybe-load (:id cust)])
       (is (= 1 (count @c))))))

  (testing "takes id from current route if not specified"
    (rf-test/run-test-sync
     (let [cust {:id "test-cust"
                 :name "Test customer"}
           c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (r/set-current {} {:path "/c/test-cust"
                                         :parameters {:path {:customer-id "test-cust"}}}))
       (h/initialize-martian {:get-customer {:status 200
                                             :body cust
                                             :error-code :no-error}})
       (rf/dispatch [:customer/maybe-load])
       (is (= 1 (count @c)))))))

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

(deftest load-orgs--failed
  (testing "redirect to login on 401 error"
    (rf-test/run-test-sync
     (let [e (h/catch-fx :route/goto)]
       (rf/dispatch [::sut/load-orgs--failed {:status 401}])
       (is (= ["/login"] @e))))))

(deftest repo-watch
  (testing "invokes repo github watch endpoint"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:watch-github-repo {:status 204
                                                  :body {:id "test-repo"}
                                                  :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:repo/watch {:id "github-id"
                                  :private false
                                  :name "test-repo"
                                  :clone-url "http://test-url"}])
       (is (= 1 (count @c)))
       (is (= :watch-github-repo (-> @c first (nth 2))))))))

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
  (testing "invokes unwatch endpoint"  
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (is (some? (reset! app-db (r/set-current {} {:parameters {:path {:customer-id "test-cust"}}}))))
       (h/initialize-martian {:unwatch-github-repo {:status 200
                                                    :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:repo/unwatch {:monkeyci/repo
                                    {:id "test-repo"}
                                    :id 12432}])
       (is (= 1 (count @c)))
       (is (= :unwatch-github-repo (-> @c first (nth 2)))
           "invokes correct endpoint")
       (is (= "test-repo" (-> @c first (nth 3) :repo-id))
           "passes repo id")
       (is (= "test-cust" (-> @c first (nth 3) :customer-id))
           "passes customer id")))))

(deftest repo-unwatch--success
  (testing "updates repo in db"
    (let [repo-id "test-repo"]
      (is (some? (reset! app-db (db/set-customer {} {:repos [{:id repo-id
                                                              :github-id "test-github-id"}]}))))
      (rf/dispatch-sync [:repo/unwatch--success {:body {:id repo-id}}])
      (is (= {:repos [{:id "test-repo"}]}
             (db/customer @app-db))))))

(deftest repo-unwatch--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:repo/unwatch--failed {:message "test error"}])
    (let [a (db/repo-alerts @app-db)]
      (is (= 1 (count a)))
      (is (= :danger (:type (first a)))))))

(deftest customer-create
  (testing "posts request to backend"
    (rf-test/run-test-sync
     (let [cust {:name "test customer"}
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:create-customer {:status 200
                                                :body cust
                                                :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:customer/create {:name ["test customer"]}])
       (is (= 1 (count @c)))
       (is (= :create-customer (-> @c first (nth 2))))
       (is (= {:customer cust} (-> @c first (nth 3)))))))

  (testing "marks creating"
    (is (nil? (rf/dispatch-sync [:customer/create {:name "new customer"}])))
    (is (true? (db/customer-creating? @app-db))))

  (testing "clears alerts"
    (is (some? (reset! app-db (db/set-create-alerts {} [{:type :info}]))))
    (is (nil? (rf/dispatch-sync [:customer/create {:name "new customer"}])))
    (is (empty? (db/create-alerts @app-db)))))

(deftest customer-create--success
  (h/catch-fx :route/goto)
  
  (testing "unmarks creating"
    (is (some? (reset! app-db (db/mark-customer-creating {}))))
    (rf/dispatch-sync [:customer/create--success {:body {:id "test-cust"}}])
    (is (not (db/customer-creating? @app-db))))

  (testing "sets customer in db"
    (let [cust {:id "test-cust"}]
      (rf/dispatch-sync [:customer/create--success {:body cust}])
      (is (= cust (db/customer @app-db)))))

  (testing "redirects to customer page"
    (rf-test/run-test-sync
     (let [e (h/catch-fx :route/goto)]
       (rf/dispatch [:customer/create--success {:body {:id "test-cust"}}])
       (is (= 1 (count @e)))
       (is (= (r/path-for :page/customer {:customer-id "test-cust"}) (first @e))))))

  (testing "sets success alert for customer"
    (let [a (db/alerts @app-db)]
      (rf/dispatch-sync [:customer/create--success {:body {:name "test customer"}}])
      (is (not-empty a))
      (is (= :success (-> a first :type))))))

(deftest customer-create--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:customer/create--failed {:message "test error"}])
    (let [a (db/create-alerts @app-db)]
      (is (= 1 (count a)))
      (is (= :danger (:type (first a)))))))

(deftest customer-load-recent-builds
  (testing "sends request to backend"
    (rf-test/run-test-sync
     (let [builds []
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-recent-builds {:status 200
                                                  :body builds
                                                  :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:customer/load-recent-builds "test-customer"])
       (is (= 1 (count @c)))
       (is (= :get-recent-builds (-> @c first (nth 2)))))))

  (testing "marks as loading"
    (rf-test/run-test-sync
     (rf/dispatch [:customer/load-recent-builds "customer-test-id"])
     (is (true? (db/loading? @app-db db/recent-builds)))))

  (testing "clears alerts"
    (rf-test/run-test-sync
     (reset! app-db (db/set-alerts {} db/recent-builds [{:type :info
                                                         :message "test alert"}]))
     (rf/dispatch [:customer/load-recent-builds "customer-test-id"])
     (is (empty? (db/get-alerts @app-db db/recent-builds))))))

(deftest customer-load-recent-builds--success
  (testing "sets builds in db"
    (let [builds [{:id ::test-build}]]
      (rf/dispatch-sync [:customer/load-recent-builds--success {:body builds}])
      (is (= builds (db/get-recent-builds @app-db)))))
  
  (testing "unmarks loading"
    (reset! app-db (db/set-loading {} db/recent-builds))
    (rf/dispatch-sync [:customer/load-recent-builds--success {:body []}])
    (is (not (db/loading? @app-db db/recent-builds)))))

(deftest customer-load-recent-builds--failed
  (testing "sets error in db"
    (rf/dispatch-sync [:customer/load-recent-builds--failed "test error"])
    (is (= [:danger] (->> (db/get-alerts @app-db db/recent-builds)
                          (map :type)))))
  
  (testing "unmarks loading"
    (reset! app-db (db/set-loading {} db/recent-builds))
    (rf/dispatch-sync [:customer/load-recent-builds--failed "test error"])
    (is (not (db/loading? @app-db db/recent-builds)))))
