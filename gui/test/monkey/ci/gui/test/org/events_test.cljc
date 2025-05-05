(ns monkey.ci.gui.test.org.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.org.db :as db]
            [monkey.ci.gui.org.events :as sut]
            [monkey.ci.gui.home.db :as hdb]
            [monkey.ci.gui.loader :as lo]
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

(deftest customer-init
  (rf-test/run-test-sync
   (rf/reg-event-db :customer/load (fn [db _] (assoc db ::loading? true)))
   (rf/dispatch [:customer/init "test-customer"])
   
   (testing "loads customer"
     (is (true? (::loading? @app-db))))

   (testing "marks initialized"
     (is (true? (lo/initialized? @app-db db/customer))))))

(deftest customer-load
  (testing "sets state to loading"
    (rf-test/run-test-sync
     (rf/dispatch [:customer/load "load-customer"])
     (is (true? (lo/loading? @app-db db/customer)))))

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
    (is (map? (reset! app-db (lo/set-loading {} db/customer))))
    (rf/dispatch-sync [:customer/load--success "test-customer"])
    (is (not (lo/loading? @app-db db/customer))))

  (testing "sets customer value"
    (rf/dispatch-sync [:customer/load--success {:body ::test-customer}])
    (is (= ::test-customer (lo/get-value @app-db db/customer)))))

(deftest customer-load--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:customer/load--failed "test-cust" "test error"])
    (let [[err] (lo/get-alerts @app-db db/customer)]
      (is (= :danger (:type err)))
      (is (re-matches #".*test-cust.*" (:message err)))))

  (testing "unmarks loading"
    (is (map? (reset! app-db (lo/set-loading {} db/customer))))
    (rf/dispatch-sync [:customer/load--failed "test-id" "test-customer"])
    (is (not (lo/loading? @app-db db/customer)))))

(deftest customer-load-latest-builds
  (testing "requests from backend"
    (rf-test/run-test-sync
     (let [cust {:id "test-cust"
                 :name "Test customer"}
           c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (r/set-current {} {:path "/c/test-cust"
                                         :parameters {:path {:customer-id "test-cust"}}}))
       (h/initialize-martian {:get-customer-latest-builds {:status 200
                                                           :body []
                                                           :error-code :no-error}})
       (rf/dispatch [:customer/load-latest-builds])
       (is (= 1 (count @c)))))))

(deftest customer-load-latest-builds--success
  (testing "sets latest builds in db by repo"
    (rf/dispatch-sync [:customer/load-latest-builds--success
                       {:body [{:repo-id "repo-1"
                                :build-id "build-1"}
                               {:repo-id "repo-2"
                                :build-id "build-2"}]}])
    (is (= {"repo-1" {:repo-id "repo-1"
                      :build-id "build-1"}
            "repo-2" {:repo-id "repo-2"
                      :build-id "build-2"}}
           (db/get-latest-builds @app-db)))))

(deftest customer-load-latest-builds--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:customer/load-latest-builds--failed "test error"])
    (let [[err] (lo/get-alerts @app-db db/customer)]
      (is (= :danger (:type err))))))

(deftest customer-load-bb-webhooks
  (testing "sends request to api"
    (rf-test/run-test-sync
     (let [cust {:name "test customer"
                 :id "test-cust"}
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:search-bitbucket-webhooks
                              {:status 200
                               :body cust
                               :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:customer/load-bb-webhooks])
       (is (= 1 (count @c)))
       (is (= :search-bitbucket-webhooks (-> @c first (nth 2))))))))

(deftest customer-load-bb-webhooks--success
  (testing "sets bitbucket webhooks in db"
    (rf/dispatch-sync [:customer/load-bb-webhooks--success {:body [::test-wh]}])
    (is (= [::test-wh] (db/bb-webhooks @app-db)))))

(deftest customer-load-bb-webhooks--failed
  (testing "sets repo alert error"
    (rf/dispatch-sync [:customer/load-bb-webhooks--failed "test error"])
    (is (= [:danger] (->> (db/repo-alerts @app-db)
                          (map :type))))))

(deftest repo-watch-github
  (testing "invokes repo github watch endpoint"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:watch-github-repo {:status 204
                                                  :body {:id "test-repo"}
                                                  :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:repo/watch-github {:id "github-id"
                                         :private false
                                         :name "test-repo"
                                         :clone-url "http://test-url"}])
       (is (= 1 (count @c)))
       (is (= :watch-github-repo (-> @c first (nth 2))))))))

(deftest repo-watch-bitbucket
  (testing "invokes repo bitbucket watch endpoint"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:watch-bitbucket-repo {:status 204
                                                     :body {:id "test-repo"}
                                                     :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:repo/watch-bitbucket {:id "github-id"
                                            :private false
                                            :name "test-repo"
                                            :links {:clone [{:name "https"
                                                             :href "https://test-url"}]}}])
       (is (= 1 (count @c)))
       (is (= :watch-bitbucket-repo (-> @c first (nth 2))))
       (is (= "https://test-url" (-> @c first (nth 3) :repo :url)))))))

(deftest repo-watch--success
  (testing "adds repo to customer"
    (is (some? (reset! app-db (db/set-customer {} {:repos []}))))
    (rf/dispatch-sync [:repo/watch--success {:body {:id "test-repo"}}])
    (is (= {:repos [{:id "test-repo"}]}
           (lo/get-value @app-db db/customer)))))

(deftest repo-watch--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:repo/watch--failed {:message "test error"}])
    (let [a (db/repo-alerts @app-db)]
      (is (= 1 (count a)))
      (is (= :danger (:type (first a)))))))

(deftest repo-unwatch-github
  (testing "invokes github unwatch endpoint"  
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (is (some? (reset! app-db (r/set-current {} {:parameters {:path {:customer-id "test-cust"}}}))))
       (h/initialize-martian {:unwatch-github-repo {:status 200
                                                    :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:repo/unwatch-github {:monkeyci/repo {:id "test-repo"}}])
       (is (= 1 (count @c)))
       (is (= :unwatch-github-repo (-> @c first (nth 2)))
           "invokes correct endpoint")
       (is (= "test-repo" (-> @c first (nth 3) :repo-id))
           "passes repo id")
       (is (= "test-cust" (-> @c first (nth 3) :customer-id))
           "passes customer id")))))

(deftest repo-unwatch-bitbucket
  (testing "invokes bitbucket unwatch endpoint"  
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (is (some? (reset! app-db (r/set-current {} {:parameters {:path {:customer-id "test-cust"}}}))))
       (h/initialize-martian {:unwatch-bitbucket-repo {:status 200
                                                       :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:repo/unwatch-bitbucket {:monkeyci/webhook
                                              {:customer-id "test-cust"
                                               :repo-id "test-repo"}}])
       (is (= 1 (count @c)))
       (is (= :unwatch-bitbucket-repo (-> @c first (nth 2)))
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
             (lo/get-value @app-db db/customer))))))

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

  (let [cust {:id "test-cust"}]
    (is (empty? (reset! app-db {})))
    (rf/dispatch-sync [:customer/create--success {:body cust}])

    (testing "sets customer in db"
      (is (= cust (lo/get-value @app-db db/customer))))

    (testing "adds to user customers"
      (is (= [cust] (hdb/get-customers @app-db)))))

  (testing "redirects to customer page"
    (rf-test/run-test-sync
     (let [e (h/catch-fx :route/goto)]
       (rf/dispatch [:customer/create--success {:body {:id "test-cust"}}])
       (is (= 1 (count @e)))
       (is (= (r/path-for :page/customer {:customer-id "test-cust"}) (first @e))))))

  (testing "sets success alert for customer"
    (let [a (lo/get-alerts @app-db db/customer)]
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
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-recent-builds {:status 200
                                                  :body []
                                                  :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:customer/load-recent-builds "test-customer"])
       (is (= 1 (count @c)))
       (is (= :get-recent-builds (-> @c first (nth 2))))))))

(deftest customer-load-recent-builds--success
  (testing "sets builds in db"
    (let [builds [{:id ::test-build}]]
      (rf/dispatch-sync [:customer/load-recent-builds--success {:body builds}])
      (is (= builds (lo/get-value @app-db db/recent-builds))))))

(deftest customer-load-recent-builds--failed
  (testing "sets error in db"
    (rf/dispatch-sync [:customer/load-recent-builds--failed "test error"])
    (is (= [:danger] (->> (lo/get-alerts @app-db db/recent-builds)
                          (map :type))))))

(deftest customer-load-stats
  (testing "sends request to backend"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-customer-stats {:status 200
                                                   :body {:stats ::test}
                                                   :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:customer/load-stats "test-customer"])
       (is (= 1 (count @c)))
       (is (= :get-customer-stats (-> @c first (nth 2)))))))

  (testing "adds `since` query param if days given"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-customer-stats {:status 200
                                                   :body {}
                                                   :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:customer/load-stats "test-customer" 10])
       (is (number? (-> @c first (nth 3) :since)))))))

(deftest customer-load-stats--success
  (testing "sets builds in db"
    (let [stats [{:stats {:elapsed-seconds []}}]]
      (rf/dispatch-sync [:customer/load-stats--success {:body stats}])
      (is (= stats (lo/get-value @app-db db/stats))))))

(deftest customer-load-stats--failed
  (testing "sets error in db"
    (rf/dispatch-sync [:customer/load-stats--failed "test error"])
    (is (= [:danger] (->> (lo/get-alerts @app-db db/stats)
                          (map :type))))))

(deftest customer-load-credits
  (testing "sends request to backend"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-customer-credits
                              {:status 200
                               :body {:available 100}
                               :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:customer/load-credits "test-customer"])
       (is (= 1 (count @c)))
       (is (= :get-customer-credits (-> @c first (nth 2))))))))

(deftest customer-load-credits--success
  (testing "sets credit info in db"
    (rf/dispatch-sync [:customer/load-credits--success {:body {:available 100}}])
    (is (= {:available 100} (db/get-credits @app-db)))))

(deftest customer-load-credits--failed
  (testing "sets error in db"
    (rf/dispatch-sync [:customer/load-credits--failed "test error"])
    (is (= [:danger] (->> (lo/get-alerts @app-db db/credits)
                          (map :type))))))

(deftest customer-event
  (testing "does nothing when recent builds not loaded"
    (rf/dispatch-sync [:customer/handle-event {:type :build/updated
                                               :build {:id "test-build"}}])
    (is (empty? @app-db)))
  
  (testing "adds build to recent builds"
    (let [build {:id "test-build"}]
      (is (some? (reset! app-db (lo/set-loaded {} db/recent-builds))))
      (rf/dispatch-sync [:customer/handle-event {:type :build/updated
                                                 :build build}])
      (is (= [build] (lo/get-value @app-db db/recent-builds)))))

  (testing "updates build in recent builds"
    (let [make-build (fn [opts]
                       (merge {:id (random-uuid)
                               :customer-id "test-cust"
                               :repo-id "test-repo"
                               :build-id (random-uuid)}
                              opts))
          build       (make-build {:build-id "build-1"
                                   :status :pending
                                   :start-time 200})
          other-build (make-build {:build-id "build-2"
                                   :status :success
                                   :start-time 100})
          upd (assoc build :status :running)]
      (is (some? (reset! app-db (-> {}
                                    (lo/set-loaded db/recent-builds)
                                    (lo/set-value db/recent-builds [build
                                                                    other-build])))))
      (rf/dispatch-sync [:customer/handle-event {:type :build/updated
                                                 :build upd}])
      (is (= [upd
              other-build]
             (lo/get-value @app-db db/recent-builds))))))

(deftest group-by-lbl-changed
  (testing "updates group-by-lbl in db"
    (is (nil? (db/group-by-lbl @app-db)))
    (rf/dispatch-sync [:customer/group-by-lbl-changed "new-label"])
    (is (= "new-label" (db/group-by-lbl @app-db)))))

(deftest repo-filter-changed
  (testing "updates repo filter in db"
    (is (nil? (db/get-repo-filter @app-db)))
    (rf/dispatch-sync [:customer/repo-filter-changed "test-filter"])
    (is (= "test-filter" (db/get-repo-filter @app-db)))))

(deftest ext-repo-filter-changed
  (testing "updates ext repo filter in db"
    (is (nil? (db/get-ext-repo-filter @app-db)))
    (rf/dispatch-sync [:customer/ext-repo-filter-changed "test-filter"])
    (is (= "test-filter" (db/get-ext-repo-filter @app-db)))))
