(ns monkey.ci.gui.test.job.events-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [clojure.string :as cs]
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.build.db :as bdb]
            [monkey.ci.gui.job.db :as db]
            [monkey.ci.gui.job.events :as sut]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as tf]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each tf/reset-db)

(deftest job-init
  (letfn [(mock-handlers []
            (rf/reg-event-db
             :org/maybe-load
             (fn [db _] (assoc db ::org ::loaded)))
            (rf/reg-event-db
             :build/load
             (fn [db _] (assoc db ::build ::loaded))))]
    
    (testing "loads org if not loaded"
      (rft/run-test-sync
       (mock-handlers)
       (rf/dispatch [:job/init])
       (is (= ::loaded (::org @app-db)))))
    
    (testing "loads build details if not present in db"
      (rft/run-test-sync
       (mock-handlers)
       (rf/dispatch [:job/init])
       (is (= ::loaded (::build @app-db)))))))

(deftest job-leave
  (let [uid (repeatedly 4 (comp str random-uuid))]
    (testing "clears all job info from db"
      (is (some? (reset! app-db (-> {}
                                    (r/set-current
                                     {:parameters
                                      {:path (zipmap [:org-id :repo-id :build-id :job-id]
                                                     uid)}})
                                    (db/set-alerts [{:type :info :message "test alert"}])))))
      (rf/dispatch-sync [:job/leave uid])
      (is (nil? (db/get-alerts @app-db))))

    (testing "clears log expansion states"
      (is (some? (reset! app-db (db/set-log-expanded {} 0 true))))
      (rf/dispatch-sync [:job/leave uid])
      (is (not (db/log-expanded? @app-db 0))))))

(deftest job-load-log-files
  (is (some? (reset! app-db (r/set-current {}
                                           {:parameters
                                            {:path
                                             {:org-id "test-org"
                                              :repo-id "test-repo"
                                              :build-id "test-build"
                                              :job-id "test-job"}}}))))
  (rft/run-test-sync
   (h/initialize-martian {:get-log-label-values {:error-code :no-error
                                                 :body {}}})
   
   (testing "sends request to label values endpoint with job filter"
     (let [e (h/catch-fx :martian.re-frame/request)]
       (is (nil? (rf/dispatch [:job/load-log-files
                               {:id "test-job"
                                :start-time 100000
                                :end-time 200000}])))
       (is (= 1 (count @e)))
       (let [params (-> @e
                        first
                        (nth 3))]
         (is (= "filename" (:label params)))
         #_(is (= 100 (:start params)))
         #_(is (= 201 (:end params))))))))

(deftest job-load-log-files--success
  (testing "clears alerts"
    (is (some? (reset! app-db (db/set-alerts {} ::test-alerts))))
    (rf/dispatch-sync [:job/load-log-files--success {}])
    (is (nil? (db/global-alerts @app-db))))
  
  (testing "sets log files in db"
    (is (empty? (reset! app-db {})))
    (rf/dispatch-sync [:job/load-log-files--success {:body {:data ::logs}}])
    (is (= ::logs (db/log-files @app-db)))))

(deftest job-load-log-files--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:job/load-log-files--failed {:message "test error"}])
    (is (= :danger (-> (db/global-alerts @app-db)
                       first
                       :type)))))

(deftest job-load-loki-logs
  (is (some? (reset! app-db (r/set-current {}
                                           {:parameters
                                            {:path
                                             {:org-id "test-org"
                                              :repo-id "test-repo"
                                              :build-id "test-build"
                                              :job-id "test-job"}}}))))

  (rft/run-test-sync

   (h/initialize-martian {:download-log {:error-code :no-error
                                         :body {}}})
   
   (let [e (h/catch-fx :martian.re-frame/request)]
     (is (nil? (rf/dispatch [:job/load-loki-logs
                             {:id "test-job"
                              :start-time 100000
                              :end-time 200000}
                             "test/path"])))
     (is (= 1 (count @e)))
     
     (testing "sends request to query range endpoint with job and filename filter"
       (is (cs/includes? (-> @e first (nth 3) :query)
                         "filename=\"test/path\"")))

     (testing "sets org id in request"
       (is (= "test-org" (-> @e first (nth 3) :org-id))))

     (testing "marks loading"
       (is (db/logs-loading? @app-db "test/path"))))))

(deftest job-load-log-ingest-logs
  (is (some? (reset! app-db (r/set-current {}
                                           {:parameters
                                            {:path
                                             {:org-id "test-org"
                                              :repo-id "test-repo"
                                              :build-id "test-build"
                                              :job-id "test-job"}}}))))

  (rft/run-test-sync

   (h/initialize-martian {:download-job-log {:error-code :no-error
                                             :body {}}})
   
   (let [e (h/catch-fx :martian.re-frame/request)]
     (is (nil? (rf/dispatch [:job/load-log-ingest-logs
                             {:id "test-job"
                              :start-time 100000
                              :end-time 200000}
                             "test/path"])))
     (is (= 1 (count @e)))
     
     (testing "specifies file"
       (is (= "test/path"
              (-> @e first (nth 3) :file))))

     (testing "sets org id in request"
       (is (= "test-org" (-> @e first (nth 3) :org-id))))

     (testing "marks loading"
       (is (db/logs-loading? @app-db "test/path"))))))

(deftest job-load-logs--success
  (testing "clears alerts"
    (let [path "test/path"]
      (is (some? (reset! app-db (db/set-alerts {} path ::test-alerts))))
      (rf/dispatch-sync [:job/load-logs--success path {}])
      (is (nil? (db/path-alerts @app-db path)))))
  
  (testing "sets log lines in db, marks src"
    (is (empty? (reset! app-db {})))
    (rf/dispatch-sync [:job/load-logs--success "test/path" :test-src {:body {:data ::logs}}])
    (is (= {:src :test-src
            :data ::logs}
           (db/get-logs @app-db "test/path"))))

  (testing "handles empty result"
    (is (empty? (reset! app-db {})))
    (rf/dispatch-sync [:job/load-logs--success "test/path" :test-src {:body ""
                                                                      :status 204}])
    (is (nil? (db/get-logs @app-db "test/path")))))

(deftest job-load-logs--failed
  (testing "sets error alert"
    (let [path "error.log"]
      (rf/dispatch-sync [:job/load-logs--failed path {:message "test error"}])
      (is (= :danger (-> (db/path-alerts @app-db path)
                         first
                         :type))))))

(deftest job-toggle-logs
  (rft/run-test-sync
   (h/initialize-martian {:download-log
                          {:error-code :no-error
                           :body {}}
                          :download-job-log
                          {:error-code :no-error
                           :body {}}})
   
   (let [e (h/catch-fx :martian.re-frame/request)
         job-id "test-job"
         build {:script {:jobs {job-id {:status :running}}}}]
     (is (some? (swap! app-db (fn [db]
                                (-> db
                                    (bdb/set-build build)
                                    (r/set-current {:parameters
                                                    {:path
                                                     {:job-id job-id}}}))))))
     
     (testing "when collapsed"
       (testing "loki src"
         (is (nil? (rf/dispatch [:job/toggle-logs 0 {:out "/path/to/out"
                                                     :err "/path/to/err"
                                                     :log-src :loki}])))

         (testing "fetches out and err logs for given line from loki style backend"
           (is (= 2 (count @e)))
           (is (every? (comp (partial = :download-log)
                             #(nth % 2))
                       @e))
           (is (-> @e
                   first
                   (nth 3)
                   :query
                   (cs/includes? "filename=\"/path/to/out\"")))
           (is (-> @e
                   second
                   (nth 3)
                   :query
                   (cs/includes? "filename=\"/path/to/err\""))))

         (testing "marks as expanded"
           (is (db/log-expanded? @app-db 0))))

       (testing "log ingest src"
         (is (empty? (reset! e [])))
         (is (some? (swap! app-db (fn [db]
                                    (db/set-log-expanded db 0 false)))))
         (is (nil? (rf/dispatch [:job/toggle-logs 0 {:out "/path/to/out"
                                                     :err "/path/to/err"
                                                     :log-src :log-ingest}])))

         (testing "fetches out and err logs for given line from log ingest style backend"
           (is (= 2 (count @e)))
           (is (every? (comp (partial = :download-job-log)
                             #(nth % 2))
                       @e))
           (is (= "/path/to/out"
                  (-> @e
                      first
                      (nth 3)
                      :file)))
           (is (= "/path/to/err"
                  (-> @e
                      second
                      (nth 3)
                      :file))))

         (testing "marks as expanded"
           (is (db/log-expanded? @app-db 0)))))

     (testing "when expanded"
       (is (empty? (reset! e [])))
       (is (nil? (rf/dispatch [:job/toggle-logs 0])))

       (testing "does not re-fetch"
         (is (empty? @e)))
       
       (testing "marks as collapsed"
         (is (not (db/log-expanded? @app-db 0)))))

     (testing "does not re-fetch logs if job finished"
       (is (empty? (reset! e [])))
       (is (some? (swap! app-db (fn [db]
                                  (bdb/set-build
                                   db
                                   (assoc-in (bdb/get-build db)
                                             [:script :jobs job-id :status] :success))))))
       (is (nil? (rf/dispatch [:job/toggle-logs 0 {:out "/path/to/out" :log-src :log-ingest}])))
       (is (empty? @e))))))

(deftest job-unblock
  (rft/run-test-sync
   (h/initialize-martian {:unblock-job {:error-code :no-error
                                        :body {}}})
   (is (some? (swap! app-db (fn [db]
                              (r/set-current db {:parameters
                                                 {:path
                                                  {:org-id "test-org"}}})))))
   (let [c (h/catch-fx :martian.re-frame/request)]
     (is (nil? (rf/dispatch [:job/unblock {:id "test-job"}])))

     (testing "invokes unblock endpoint"
       (is (= 1 (count @c)))
       (is (= :unblock-job (-> @c first (nth 2))))
       (is (= {:job-id "test-job"
               :org-id "test-org"}
              (-> @c first (nth 3)))
           "passes job sid as params"))

     (testing "marks unblocking"
       (is (true? (db/unblocking? @app-db)))))))

(deftest job-unblock--success
  (is (some? (reset! app-db (db/set-unblocking {}))))
  (is (nil? (rf/dispatch-sync [:job/unblock--success {}])))
  
  (testing "unmarks unblocking"
    (is (false? (db/unblocking? @app-db)))))

(deftest job-unblock--failure
  (is (some? (reset! app-db (db/set-unblocking {}))))
  (is (nil? (rf/dispatch-sync [:job/unblock--failure {:message "test error"}])))
  
  (testing "unmarks unblocking"
    (is (false? (db/unblocking? @app-db))))

  (testing "sets error alert"
    (is (= [:danger]
           (->> (db/get-alerts @app-db)
                (map :type))))))

(deftest job-wrap-logs-changed
  (testing "toggles job wrap logs flag"
    (rf/dispatch-sync [:job/wrap-logs-changed true])
    (is (true? (db/wrap-logs? @app-db)))))
