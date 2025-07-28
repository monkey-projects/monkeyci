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

(deftest org-init
  (rf-test/run-test-sync
   (rf/reg-event-db :org/load (fn [db _] (assoc db ::loading? true)))
   (rf/dispatch [:org/init "test-org"])
   
   (testing "loads org"
     (is (true? (::loading? @app-db))))

   (testing "marks initialized"
     (is (true? (lo/initialized? @app-db db/org))))))

(deftest org-load
  (testing "sets state to loading"
    (rf-test/run-test-sync
     (rf/dispatch [:org/load "load-org"])
     (is (true? (lo/loading? @app-db db/org)))))

  (testing "sends request to api and sets org"
    (rf-test/run-test-sync
     (let [org {:name "test org"}
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-org {:status 200
                                        :body org
                                        :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:org/load "test-org"])
       (is (= 1 (count @c)))
       (is (= :get-org (-> @c first (nth 2))))))))

(deftest org-maybe-load
  (testing "loads org if not in db"
    (rf-test/run-test-sync
     (let [org {:id "test-org"
                :name "Test org"}
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-org {:status 200
                                        :body org
                                        :error-code :no-error}})
       (rf/dispatch [:org/maybe-load (:id org)])
       (is (= 1 (count @c))))))

  (testing "does not load if already in db"
    (rf-test/run-test-sync
     (let [org {:id "test-org"
                :name "Test org"}
           c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (db/set-org {} org))
       (h/initialize-martian {:get-org {:status 200
                                        :body org
                                        :error-code :no-error}})
       (rf/dispatch [:org/maybe-load (:id org)])
       (is (empty? @c)))))

  (testing "loads if id differs from id in db"
    (rf-test/run-test-sync
     (let [org {:id "test-org"
                :name "Test org"}
           c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (db/set-org {} {:id "other-org"}))
       (h/initialize-martian {:get-org {:status 200
                                        :body org
                                        :error-code :no-error}})
       (rf/dispatch [:org/maybe-load (:id org)])
       (is (= 1 (count @c))))))

  (testing "takes id from current route if not specified"
    (rf-test/run-test-sync
     (let [org {:id "test-org"
                :name "Test org"}
           c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (r/set-current {} {:path "/o/test-org"
                                         :parameters {:path {:org-id "test-org"}}}))
       (h/initialize-martian {:get-org {:status 200
                                        :body org
                                        :error-code :no-error}})
       (rf/dispatch [:org/maybe-load])
       (is (= 1 (count @c)))))))

(deftest org-load--success
  (testing "unmarks loading"
    (is (map? (reset! app-db (lo/set-loading {} db/org))))
    (rf/dispatch-sync [:org/load--success "test-org"])
    (is (not (lo/loading? @app-db db/org))))

  (testing "sets org value"
    (rf/dispatch-sync [:org/load--success {:body ::test-org}])
    (is (= ::test-org (lo/get-value @app-db db/org)))))

(deftest org-load--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:org/load--failed "test-org" "test error"])
    (let [[err] (lo/get-alerts @app-db db/org)]
      (is (= :danger (:type err)))
      (is (re-matches #".*test-org.*" (:message err)))))

  (testing "unmarks loading"
    (is (map? (reset! app-db (lo/set-loading {} db/org))))
    (rf/dispatch-sync [:org/load--failed "test-id" "test-org"])
    (is (not (lo/loading? @app-db db/org)))))

(deftest org-load-latest-builds
  (testing "requests from backend"
    (rf-test/run-test-sync
     (let [org {:id "test-org"
                :name "Test org"}
           c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (r/set-current {} {:path "/c/test-org"
                                         :parameters {:path {:org-id "test-org"}}}))
       (h/initialize-martian {:get-org-latest-builds {:status 200
                                                      :body []
                                                      :error-code :no-error}})
       (rf/dispatch [:org/load-latest-builds])
       (is (= 1 (count @c)))))))

(deftest org-load-latest-builds--success
  (testing "sets latest builds in db by repo"
    (rf/dispatch-sync [:org/load-latest-builds--success
                       {:body [{:repo-id "repo-1"
                                :build-id "build-1"}
                               {:repo-id "repo-2"
                                :build-id "build-2"}]}])
    (is (= {"repo-1" {:repo-id "repo-1"
                      :build-id "build-1"}
            "repo-2" {:repo-id "repo-2"
                      :build-id "build-2"}}
           (db/get-latest-builds @app-db)))))

(deftest org-load-latest-builds--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:org/load-latest-builds--failed "test error"])
    (let [[err] (lo/get-alerts @app-db db/org)]
      (is (= :danger (:type err))))))

(deftest org-load-bb-webhooks
  (testing "sends request to api"
    (rf-test/run-test-sync
     (let [org {:name "test org"
                :id "test-org"}
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:search-bitbucket-webhooks
                              {:status 200
                               :body org
                               :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:org/load-bb-webhooks])
       (is (= 1 (count @c)))
       (is (= :search-bitbucket-webhooks (-> @c first (nth 2))))))))

(deftest org-load-bb-webhooks--success
  (testing "sets bitbucket webhooks in db"
    (rf/dispatch-sync [:org/load-bb-webhooks--success {:body [::test-wh]}])
    (is (= [::test-wh] (db/bb-webhooks @app-db)))))

(deftest org-load-bb-webhooks--failed
  (testing "sets repo alert error"
    (rf/dispatch-sync [:org/load-bb-webhooks--failed "test error"])
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
  (rf-test/run-test-sync
   (let [c (h/catch-fx :route/goto)
         repo {:org-id "test-org"
               :id "test-repo"}]
     (testing "adds repo to org"
       (is (some? (reset! app-db (db/set-org {} {:repos []}))))
       (rf/dispatch [:repo/watch--success {:body repo}])
       (is (= {:repos [repo]}
              (lo/get-value @app-db db/org))))

     (testing "redirects to repo edit page"
       (is (= 1 (count @c)))
       (is (= (r/path-for :page/repo-settings {:repo-id "test-repo" :org-id "test-org"})
              (first @c)))))))

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
       (is (some? (reset! app-db (r/set-current {} {:parameters {:path {:org-id "test-org"}}}))))
       (h/initialize-martian {:unwatch-github-repo {:status 200
                                                    :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:repo/unwatch-github {:monkeyci/repo {:id "test-repo"}}])
       (is (= 1 (count @c)))
       (is (= :unwatch-github-repo (-> @c first (nth 2)))
           "invokes correct endpoint")
       (is (= "test-repo" (-> @c first (nth 3) :repo-id))
           "passes repo id")
       (is (= "test-org" (-> @c first (nth 3) :org-id))
           "passes org id")))))

(deftest repo-unwatch-bitbucket
  (testing "invokes bitbucket unwatch endpoint"  
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (is (some? (reset! app-db (r/set-current {} {:parameters {:path {:org-id "test-org"}}}))))
       (h/initialize-martian {:unwatch-bitbucket-repo {:status 200
                                                       :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:repo/unwatch-bitbucket {:monkeyci/webhook
                                              {:org-id "test-org"
                                               :repo-id "test-repo"}}])
       (is (= 1 (count @c)))
       (is (= :unwatch-bitbucket-repo (-> @c first (nth 2)))
           "invokes correct endpoint")
       (is (= "test-repo" (-> @c first (nth 3) :repo-id))
           "passes repo id")
       (is (= "test-org" (-> @c first (nth 3) :org-id))
           "passes org id")))))

(deftest repo-unwatch--success
  (testing "updates repo in db"
    (let [repo-id "test-repo"]
      (is (some? (reset! app-db (db/set-org {} {:repos [{:id repo-id
                                                         :github-id "test-github-id"}]}))))
      (rf/dispatch-sync [:repo/unwatch--success {:body {:id repo-id}}])
      (is (= {:repos [{:id "test-repo"}]}
             (lo/get-value @app-db db/org))))))

(deftest repo-unwatch--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:repo/unwatch--failed {:message "test error"}])
    (let [a (db/repo-alerts @app-db)]
      (is (= 1 (count a)))
      (is (= :danger (:type (first a)))))))

(deftest org-create
  (testing "posts request to backend"
    (rf-test/run-test-sync
     (let [org {:name "test org"}
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:create-org {:status 200
                                           :body org
                                           :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:org/create {:name ["test org"]}])
       (is (= 1 (count @c)))
       (is (= :create-org (-> @c first (nth 2))))
       (is (= {:org org} (-> @c first (nth 3)))))))

  (testing "marks creating"
    (is (nil? (rf/dispatch-sync [:org/create {:name "new org"}])))
    (is (true? (db/org-creating? @app-db))))

  (testing "clears alerts"
    (is (some? (reset! app-db (db/set-create-alerts {} [{:type :info}]))))
    (is (nil? (rf/dispatch-sync [:org/create {:name "new org"}])))
    (is (empty? (db/create-alerts @app-db)))))

(deftest org-create--success
  (h/catch-fx :route/goto)
  
  (testing "unmarks creating"
    (is (some? (reset! app-db (db/mark-org-creating {}))))
    (rf/dispatch-sync [:org/create--success {:body {:id "test-org"}}])
    (is (not (db/org-creating? @app-db))))

  (let [org {:id "test-org"}]
    (is (empty? (reset! app-db {})))
    (rf/dispatch-sync [:org/create--success {:body org}])

    (testing "sets org in db"
      (is (= org (lo/get-value @app-db db/org))))

    (testing "adds to user orgs"
      (is (= [org] (hdb/get-orgs @app-db)))))

  (testing "redirects to org page"
    (rf-test/run-test-sync
     (let [e (h/catch-fx :route/goto)]
       (rf/dispatch [:org/create--success {:body {:id "test-org"}}])
       (is (= 1 (count @e)))
       (is (= (r/path-for :page/org {:org-id "test-org"}) (first @e))))))

  (testing "sets success alert for org"
    (let [a (lo/get-alerts @app-db db/org)]
      (rf/dispatch-sync [:org/create--success {:body {:name "test org"}}])
      (is (not-empty a))
      (is (= :success (-> a first :type))))))

(deftest org-create--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:org/create--failed {:message "test error"}])
    (let [a (db/create-alerts @app-db)]
      (is (= 1 (count a)))
      (is (= :danger (:type (first a)))))))

(deftest org-load-recent-builds
  (testing "sends request to backend"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-recent-builds {:status 200
                                                  :body []
                                                  :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:org/load-recent-builds "test-org"])
       (is (= 1 (count @c)))
       (is (= :get-recent-builds (-> @c first (nth 2))))))))

(deftest org-load-recent-builds--success
  (testing "sets builds in db"
    (let [builds [{:id ::test-build}]]
      (rf/dispatch-sync [:org/load-recent-builds--success {:body builds}])
      (is (= builds (lo/get-value @app-db db/recent-builds))))))

(deftest org-load-recent-builds--failed
  (testing "sets error in db"
    (rf/dispatch-sync [:org/load-recent-builds--failed "test error"])
    (is (= [:danger] (->> (lo/get-alerts @app-db db/recent-builds)
                          (map :type))))))

(deftest org-load-stats
  (testing "sends request to backend"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-org-stats {:status 200
                                              :body {:stats ::test}
                                              :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:org/load-stats "test-org"])
       (is (= 1 (count @c)))
       (is (= :get-org-stats (-> @c first (nth 2)))))))

  (testing "adds `since` query param if days given"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-org-stats {:status 200
                                              :body {}
                                              :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:org/load-stats "test-org" 10])
       (is (number? (-> @c first (nth 3) :since)))))))

(deftest org-load-stats--success
  (testing "sets builds in db"
    (let [stats [{:stats {:elapsed-seconds []}}]]
      (rf/dispatch-sync [:org/load-stats--success {:body stats}])
      (is (= stats (lo/get-value @app-db db/stats))))))

(deftest org-load-stats--failed
  (testing "sets error in db"
    (rf/dispatch-sync [:org/load-stats--failed "test error"])
    (is (= [:danger] (->> (lo/get-alerts @app-db db/stats)
                          (map :type))))))

(deftest org-load-credits
  (testing "sends request to backend"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-org-credits
                              {:status 200
                               :body {:available 100}
                               :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:org/load-credits "test-org"])
       (is (= 1 (count @c)))
       (is (= :get-org-credits (-> @c first (nth 2))))))))

(deftest org-load-credits--success
  (testing "sets credit info in db"
    (rf/dispatch-sync [:org/load-credits--success {:body {:available 100}}])
    (is (= {:available 100} (db/get-credits @app-db)))))

(deftest org-load-credits--failed
  (testing "sets error in db"
    (rf/dispatch-sync [:org/load-credits--failed "test error"])
    (is (= [:danger] (->> (lo/get-alerts @app-db db/credits)
                          (map :type))))))

(deftest org-event
  (testing "does nothing when recent builds not loaded"
    (rf/dispatch-sync [:org/handle-event {:type :build/updated
                                          :build {:id "test-build"}}])
    (is (empty? @app-db)))
  
  (testing "adds build to recent builds"
    (let [build {:id "test-build"}]
      (is (some? (reset! app-db (lo/set-loaded {} db/recent-builds))))
      (rf/dispatch-sync [:org/handle-event {:type :build/updated
                                            :build build}])
      (is (= [build] (lo/get-value @app-db db/recent-builds)))))

  (testing "updates build in recent builds"
    (let [make-build (fn [opts]
                       (merge {:id (random-uuid)
                               :customer-id "test-org"
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
      (rf/dispatch-sync [:org/handle-event {:type :build/updated
                                            :build upd}])
      (is (= [upd
              other-build]
             (lo/get-value @app-db db/recent-builds))))))

(deftest group-by-lbl-changed
  (testing "updates group-by-lbl in db"
    (is (nil? (db/group-by-lbl @app-db)))
    (rf/dispatch-sync [:org/group-by-lbl-changed "new-label"])
    (is (= "new-label" (db/group-by-lbl @app-db)))))

(deftest repo-filter-changed
  (testing "updates repo filter in db"
    (is (nil? (db/get-repo-filter @app-db)))
    (rf/dispatch-sync [:org/repo-filter-changed "test-filter"])
    (is (= "test-filter" (db/get-repo-filter @app-db)))))

(deftest ext-repo-filter-changed
  (testing "updates ext repo filter in db"
    (is (nil? (db/get-ext-repo-filter @app-db)))
    (rf/dispatch-sync [:org/ext-repo-filter-changed "test-filter"])
    (is (= "test-filter" (db/get-ext-repo-filter @app-db)))))

(deftest org-save
  (testing "posts request to backend"
    (rf-test/run-test-sync
     (let [org {:name "test org"}
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:update-org {:status 200
                                           :body org
                                           :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (is (some? (swap! app-db r/set-current
                         {:parameters
                          {:path
                           {:org-id "test-org"}}})))
       (rf/dispatch [:org/save {:name ["test org"]}])
       (is (= 1 (count @c)))
       (is (= :update-org (-> @c first (nth 2))))
       (is (= {:org
               {:name "test org"
                :id "test-org"}
               :org-id "test-org"}
              (-> @c first (nth 3)))))))

  (testing "clears alerts"
    (is (some? (reset! app-db (db/set-edit-alerts {} [{:type :info}]))))
    (is (nil? (rf/dispatch-sync [:org/save {:name "new org"}])))
    (is (empty? (db/edit-alerts @app-db)))))

(deftest org-save--success
  (h/catch-fx :route/goto)
  
  (let [org {:id "test-org"}]
    (is (empty? (reset! app-db {})))
    (rf/dispatch-sync [:org/save--success {:body org}])

    (testing "sets org in db"
      (is (= org (lo/get-value @app-db db/org)))))

  (testing "redirects to org page"
    (rf-test/run-test-sync
     (let [e (h/catch-fx :route/goto)]
       (rf/dispatch [:org/save--success {:body {:id "test-org"}}])
       (is (= 1 (count @e)))
       (is (= (r/path-for :page/org {:org-id "test-org"}) (first @e))))))

  (testing "sets success alert for org"
    (let [a (lo/get-alerts @app-db db/org)]
      (rf/dispatch-sync [:org/save--success {:body {:name "test org"}}])
      (is (not-empty a))
      (is (= :success (-> a first :type))))))

(deftest org-save--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:org/save--failed {:message "test error"}])
    (let [a (db/edit-alerts @app-db)]
      (is (= 1 (count a)))
      (is (= :danger (:type (first a)))))))
