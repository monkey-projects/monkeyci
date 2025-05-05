(ns monkey.ci.gui.test.repo.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.org.db :as cdb]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.repo.db :as db]
            [monkey.ci.gui.repo.events :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(defn- set-repo-path [db org repo]
  (r/set-current db {:parameters
                     {:path 
                      {:org-id org
                       :repo-id repo}}}))

(defn- set-repo-path! [org repo]
  (swap! app-db set-repo-path org repo))

(defn- test-repo-path!
  "Generate random ids for org, repo and build, and sets the current
   route to the generated path.  Returns the generated ids."
  []
  (let [[org repo _ :as r] (repeatedly 3 random-uuid)]
    (set-repo-path! org repo)
    r))

(deftest repo-init
  (testing "does nothing if initialized"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (lo/set-initialized {} db/id))
       (h/initialize-martian {:get-org {:error-code :unexpected}})

       (rf/dispatch [:repo/init])
       (is (empty? @c)))))

  (testing "when not initialized"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-org {:body {}
                                             :error-code :no-error}})
       (rf/dispatch [:repo/init])
       
       (testing "loads repo"
         (is (= 1 (count @c))))

       (testing "sets initialized"
         (is (lo/initialized? @app-db db/id)))))))

(deftest repo-load
  (testing "loads org if not existing"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-org {:body {:name "test org"}
                                             :error-code :no-error}})
       
       (rf/dispatch [:repo/load "test-org-id"])
       (is (= 1 (count @c)))
       (is (= :get-org (-> @c first (nth 2)))))))

  (testing "does not load org if already loaded"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (cdb/set-org {} {:name "existing org"}))
       (h/initialize-martian {:get-org {:body {:name "test org"}
                                             :error-code :no-error}})
       
       (rf/dispatch [:repo/load "test-org-id"])
       (is (empty? @c)))))

  (testing "clears builds"
    (is (some? (reset! app-db (db/set-builds {} ::test-builds))))
    (rf/dispatch-sync [:repo/load "other-org-id"])
    (is (nil? (db/get-builds @app-db)))))

(deftest repo-leave
  (testing "clears builds from db"
    (is (some? (reset! app-db (db/set-builds {} ::test-builds))))
    (rf/dispatch-sync [:repo/leave])
    (is (nil? (db/get-builds @app-db)))))

(deftest builds-load
  (testing "fetches builds from backend"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (set-repo-path! "test-org" "test-repo")
       (h/initialize-martian {:get-builds {:body [{:id "test-build"}]
                                           :error-code :no-error}})
       (rf/dispatch [:builds/load])
       (is (= 1 (count @c)))
       (is (= :get-builds (-> @c first (nth 2)))))))

  (testing "does not clear current builds"
    (is (map? (reset! app-db (db/set-builds {} [{:id "initial-build"}]))))
    (rf/dispatch-sync [:builds/load])
    (is (some? (db/get-builds @app-db)))))

(deftest build-load-success
  (testing "sets builds"
    (let [builds [{:id ::test-build}]]
      (rf/dispatch-sync [:builds/load--success {:body builds}])
      (is (= builds (db/get-builds @app-db))))))

(deftest handle-event
  (testing "ignores events for other repos"
    (let [[org] (test-repo-path!)]
      (is (nil? (rf/dispatch-sync [:repo/handle-event {:type :build/start
                                                       :build {:org-id org
                                                               :repo-id "other-repo"
                                                               :build-id "other-build"
                                                               :git {:ref "main"}}}])))
      (is (empty? (db/get-builds @app-db)))))

  (testing "updates build list when build is started"
    (reset! app-db {})
    (let [[org repo build] (test-repo-path!)]
      (is (empty? (db/get-builds @app-db)))
      (is (nil? (rf/dispatch-sync [:repo/handle-event {:type :build/start
                                                       :build {:org-id org
                                                               :repo-id repo
                                                               :build-id build
                                                               :git {:ref "main"}}}])))
      (is (= [{:org-id org
               :repo-id repo
               :build-id build
               :git {:ref "main"}}]
             (db/get-builds @app-db)))))

  (testing "updates build list when build is pending"
    (reset! app-db {})
    (let [[org repo build] (test-repo-path!)]
      (is (empty? (db/get-builds @app-db)))
      (is (nil? (rf/dispatch-sync [:repo/handle-event {:type :build/pending
                                                       :build {:org-id org
                                                               :repo-id repo
                                                               :build-id build
                                                               :git {:ref "main"}}}])))
      (is (= [{:org-id org
               :repo-id repo
               :build-id build
               :git {:ref "main"}}]
             (db/get-builds @app-db)))))

  (testing "updates build list when build has updated"
    (reset! app-db {})
    (let [[org repo build] (test-repo-path!)
          upd {:org-id org
               :repo-id repo
               :build-id build
               :git {:ref "main"}
               :status :success}]
      (is (some? (swap! app-db db/set-builds [{:org-id org
                                               :repo-id repo
                                               :build-id build}])))
      (is (nil? (rf/dispatch-sync [:repo/handle-event {:type :build/updated
                                                       :build upd}])))
      (is (= [upd]
             (db/get-builds @app-db))))))

(deftest show-trigger-build
  (testing "sets `show trigger form` flag in db"
    (rf/dispatch-sync [:repo/show-trigger-build])
    (is (db/show-trigger-form? @app-db))))

(deftest hide-trigger-build
  (testing "unsets `show trigger form` flag in db"
    (reset! app-db (db/set-show-trigger-form {} true))
    (rf/dispatch-sync [:repo/hide-trigger-build])
    (is (nil? (db/show-trigger-form? @app-db)))))

(deftest trigger-build
  (testing "sets `triggering` flag"
    (rf/dispatch-sync [:repo/trigger-build])
    (is (true? (db/triggering? @app-db))))

  (testing "invokes build trigger endpoint with params"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (is (some? (set-repo-path! "test-org" "test-repo")))
       (h/initialize-martian {:trigger-build {:body {:build-id "test-build"}
                                              :error-code :no-error}})
       (rf/dispatch [:repo/trigger-build {:trigger-type ["branch"]
                                          :trigger-ref ["main"]}])
       
       (is (= 1 (count @c)))
       (is (= {:branch "main"
               :org-id "test-org"
               :repo-id "test-repo"}
              (-> @c first (nth 3)))))))

  (testing "clears notifications"
    (is (some? (reset! app-db (db/set-alerts {} [{:type :danger}]))))
    (rf/dispatch-sync [:repo/trigger-build])
    (is (empty? (db/alerts @app-db)))))

(deftest trigger-build--success
  (testing "sets notification alert"
    (rf/dispatch-sync [:repo/trigger-build--success {:body {:build-id "test-build"}}])
    (is (= :info (-> (db/alerts @app-db) first :type))))

  (testing "hides trigger form"
    (is (some? (reset! app-db (db/set-show-trigger-form {} true))))
    (rf/dispatch-sync [:repo/trigger-build--success {:body {:build-id "test-build"}}])
    (is (not (db/show-trigger-form? @app-db)))))

(deftest trigger-build--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:repo/trigger-build--failed {:body {:message "test error"}}])
    (is (= :danger (-> (db/alerts @app-db) first :type)))))

(deftest repo-load+edit
  (rft/run-test-sync
   (let [c (h/catch-fx :martian.re-frame/request)
         [_ repo-id _] (test-repo-path!)
         repo {:id repo-id
               :name "test repo"}
         org {:repos [repo]}]
     (reset! app-db (db/set-edit-alerts {} [{:type :warning}]))
     (h/initialize-martian {:get-org {:body org
                                           :error-code :no-error}})
     (rf/dispatch [:repo/load+edit])

     (testing "loads org from backend"
       (is (= 1 (count @c)))
       (is (= :get-org (-> @c first (nth 2)))))

     (testing "clears alerts"
       (is (empty? (db/edit-alerts @app-db)))))))

(deftest repo-load+edit--success
  (rft/run-test-sync
   (let [[_ repo-id] (test-repo-path!)
         repo {:id repo-id
               :name "test repo"}
         org {:repos [repo]}]

     (rf/dispatch [:repo/load+edit--success {:body org}])
     
     (testing "sets current org"
       (is (= org (lo/get-value @app-db cdb/org))))
     
     (testing "sets repo for editing"
       (is (= repo (db/editing @app-db)))))))

(deftest repo-load+edit--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:repo/load+edit--failed {:body {:message "test error"}}])
    (is (= :danger (-> (db/edit-alerts @app-db) first :type)))))

(deftest repo-label-add
  (testing "adds new label to editing repo"
    (rf/dispatch-sync [:repo/label-add])
    (is (= 1 (count (-> (db/editing @app-db)
                        :labels))))))

(deftest repo-label-removed
  (testing "removes given label from editing repo"
    (let [lbl {:name "test label" :value "test value"}]
      (is (some? (reset! app-db (db/set-editing {} {:labels [lbl]}))))
      (rf/dispatch-sync [:repo/label-removed lbl])
      (is (empty? (-> (db/editing @app-db)
                      :labels))))))

(deftest repo-label-name-changed
  (testing "updates name for given label"
    (let [lbl {:name "test label" :value "test value"}]
      (is (some? (reset! app-db (db/set-editing {} {:labels [lbl]}))))
      (rf/dispatch-sync [:repo/label-name-changed lbl "updated label"])
      (is (= {:name "updated label"
              :value "test value"}
             (-> (db/editing @app-db)
                 :labels
                 first))))))

(deftest repo-label-value-changed
  (testing "updates value for given label"
    (let [lbl {:name "test label" :value "test value"}]
      (is (some? (reset! app-db (db/set-editing {} {:labels [lbl]}))))
      (rf/dispatch-sync [:repo/label-value-changed lbl "updated value"])
      (is (= {:name "test label"
              :value "updated value"}
             (-> (db/editing @app-db)
                 :labels
                 first))))))

(deftest repo-name-changed
  (testing "updates editing repo name"
    (rf/dispatch-sync [:repo/name-changed "new name"])
    (is (= "new name" (:name (db/editing @app-db))))))

(deftest repo-main-branch-changed
  (testing "updates editing repo main-branch"
    (rf/dispatch-sync [:repo/main-branch-changed "new branch"])
    (is (= "new branch" (:main-branch (db/editing @app-db))))))

(deftest repo-url-changed
  (testing "updates editing repo url"
    (rf/dispatch-sync [:repo/url-changed "new url"])
    (is (= "new url" (:url (db/editing @app-db))))))

(deftest repo-save
  (rft/run-test-sync
   (let [c (h/catch-fx :martian.re-frame/request)
         [org-id repo-id] (test-repo-path!)]
     (swap! app-db #(-> %
                        (db/set-editing {:name "updated repo name"})
                        (db/set-edit-alerts [{:type :warning}])))
     (h/initialize-martian {:update-repo {:error-code :no-error}})

     (is (not-empty (r/path-params (r/current @app-db))))

     (rf/dispatch [:repo/save])

     (testing "updates repo in backend"
       (is (= 1 (count @c))))

     (testing "adds org and repo id to body"
       (let [args (-> @c first (nth 3) :repo)]
         (is (map? args))
         (is (= org-id (:org-id args)))
         (is (= repo-id (:id args)))))

     (testing "adds org and repo id to params"
       (let [args (-> @c first (nth 3))]
         (is (map? args))
         (is (= org-id (:org-id args)))
         (is (= repo-id (:repo-id args)))))

     (testing "marks saving"
       (is (db/saving? @app-db)))

     (testing "clears alerts"
       (is (empty? (db/edit-alerts @app-db)))))))

(deftest repo-save--success
  (testing "updates repo in db"
    (reset! app-db (cdb/set-org {}
                                {:name "test org"
                                 :repos [{:id "test-repo"
                                          :name "original repo"}]}))
    (rf/dispatch-sync [:repo/save--success {:body {:id "test-repo"
                                                   :name "updated repo"}}])
    (is (= "updated repo"
           (-> @app-db
               (lo/get-value cdb/org)
               :repos
               first
               :name))))

  (testing "unmarks saving"
    (reset! app-db (db/mark-saving {}))
    (rf/dispatch-sync [:repo/save--success {}])
    (is (not (db/saving? @app-db))))

  (testing "sets success alert"
    (rf/dispatch-sync [:repo/save--success {}])
    (is (= 1 (count (db/edit-alerts @app-db))))
    (is (= :success (-> (db/edit-alerts @app-db)
                        first
                        :type)))))

(deftest repo-save--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:repo/save--failed {}])
    (is (= 1 (count (db/edit-alerts @app-db))))
    (is (= :danger (-> (db/edit-alerts @app-db)
                       first
                       :type))))

  (testing "unmarks saving"
    (reset! app-db (db/mark-saving {}))
    (rf/dispatch-sync [:repo/save--failed {}])
    (is (not (db/saving? @app-db)))))

(deftest repo-delete
  (rft/run-test-sync
   (let [c (h/catch-fx :martian.re-frame/request)
         [org-id repo-id] (test-repo-path!)]
     (swap! app-db #(db/set-editing % {:id "test-repo"}))
     (h/initialize-martian {:delete-repo {:error-code :no-error}})

     (rf/dispatch [:repo/delete])
     
     (testing "sends delete request to backend"
       (is (not-empty @c))
       (is (= :delete-repo (-> @c first (nth 2)))))

     (testing "marks as deleting"
       (is (db/deleting? @app-db))))))

(deftest repo-delete--success
  (rft/run-test-sync
   (let [repo {:id "test-repo"}
         org {:id "test-org"
               :repos [repo]}
         e (h/catch-fx :route/goto)]
     (is (some? (reset! app-db (-> {}
                                   (set-repo-path (:id org) (:id repo))
                                   (cdb/set-org org)
                                   (db/mark-deleting)))))
     (rf/dispatch [:repo/delete--success])
     
     (testing "removes repo from db"
       (is (empty? (-> (cdb/get-org @app-db) :repos))))

     (testing "unmarks deleting"
       (is (not (db/deleting? @app-db))))

     (testing "sets org alert"
       (is (= :info (-> (cdb/get-alerts @app-db)
                        first
                        :type))))

     (testing "redirects to org page"
       (is (= 1 (count @e)))))))

(deftest repo-delete--failed
  (is (some? (set-repo-path! "test-org" "test-repo")))
  (is (some? (swap! app-db db/mark-deleting)))
  (rf/dispatch-sync [:repo/delete--failed])
  
  (testing "unmarks deleting"
    (is (not (db/deleting? @app-db))))

  (testing "sets repo edit alert"
    (is (= :danger (-> (db/edit-alerts @app-db)
                       first
                       :type)))))
