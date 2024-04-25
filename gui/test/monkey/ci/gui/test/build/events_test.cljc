(ns monkey.ci.gui.test.build.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.customer.db :as cdb]
            [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.build.events :as sut]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest build-init
  (letfn [(mock-handlers []
            (rf/reg-event-db :build/load (constantly nil))
            (rf/reg-event-db :build/load-logs (constantly nil))
            (rf/reg-event-db :event-stream/start (constantly nil))
            (rf/reg-event-db :route/on-page-leave (constantly nil)))]
    
    (testing "when not initialized"
      (testing "dispatches load event"
        (rft/run-test-sync
         (let [c (atom [])]
           (mock-handlers)
           (rf/reg-event-db :build/load (fn [_ evt] (swap! c conj evt)))
           (rf/dispatch [:build/init])
           (is (= 1 (count @c))))))
      
      (testing "dispatches log load event"
        (rft/run-test-sync
         (let [c (atom [])]
           (mock-handlers)
           (rf/reg-event-db :build/load-logs (fn [_ evt] (swap! c conj evt)))
           (rf/dispatch [:build/init])
           (is (= 1 (count @c))))))

      (testing "dispatches page leave event"
        (rft/run-test-sync
         (let [c (atom [])]
           (mock-handlers)
           (rf/reg-event-db :route/on-page-leave (fn [_ evt] (swap! c conj evt)))
           (rf/dispatch [:build/init])
           (is (= 1 (count @c))))))

      (testing "dispatches event stream start"
        (rft/run-test-sync
         (let [c (atom [])]
           (mock-handlers)
           (rf/reg-event-db :event-stream/start (fn [_ evt] (swap! c conj evt)))
           (rf/dispatch [:build/init])
           (is (= 1 (count @c))))))

      (testing "marks initialized"
        (rft/run-test-sync
         (mock-handlers)
         (reset! app-db (db/set-logs {} ::test-logs))
         (rf/dispatch [:build/init])
         (is (true? (db/initialized? @app-db))))))

    (testing "does nothing when initialized"
      (reset! app-db (db/set-initialized {} true))
      (rft/run-test-sync
       (mock-handlers)
       (let [c (atom [])
             h (fn [_ evt] (swap! c conj evt))]
         (rf/reg-event-db :build/load h)
         (rf/reg-event-db :event-stream/start h)
         (rf/reg-event-db :route/on-page-leave h)
         (rf/dispatch [:build/init])
         (is (empty? @c)))))))

(deftest build-load-logs
  (testing "sets alert"
    (rf/dispatch-sync [:build/load-logs])
    (is (= 1 (count (db/alerts @app-db)))))

  (testing "fetches builds from backend"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db {:route/current
                       {:parameters
                        {:path 
                         {:customer-id "test-cust"
                          :repo-id "test-repo"
                          :build-id "test-build"}}}})
       (h/initialize-martian {:get-build-logs {:body ["test-log"]
                                               :error-code :no-error}})
       (rf/dispatch [:build/load-logs])
       (is (= 1 (count @c)))
       (is (= :get-build-logs (-> @c first (nth 2)))))))

  (testing "clears current logs"
    (is (map? (reset! app-db (db/set-logs {} ["test-log"]))))
    (rf/dispatch-sync [:build/load-logs])
    (is (nil? (db/logs @app-db)))))

(deftest build-load-logs--success
  (testing "clears alerts"
    (is (map? (reset! app-db (db/set-alerts {} [{:type :info
                                                 :message "test notification"}]))))
    (rf/dispatch-sync [:build/load-logs--success {:body []}])
    (is (nil? (db/alerts @app-db))))

  (testing "clears logs reload flag"
    (is (map? (reset! app-db (db/set-reloading {} #{:logs}))))
    (rf/dispatch-sync [:build/load-logs--success {:body []}])
    (is (not (db/reloading? @app-db)))))

(deftest build-load-logs--failed
  (testing "sets error"
    (rf/dispatch-sync [:build/load-logs--failed "test-error"])
    (is (= :danger (-> (db/alerts @app-db)
                       (first)
                       :type))))

  (testing "clears logs reload flag"
    (is (map? (reset! app-db (db/set-reloading {} #{:logs}))))
    (rf/dispatch-sync [:build/load-logs--failed {:body {}}])
    (is (not (db/reloading? @app-db)))))

(deftest build-load
  (testing "sets alert"
    (rf/dispatch-sync [:build/load])
    (is (= 1 (count (db/alerts @app-db)))))

  (testing "fetches builds from backend"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db {:route/current
                       {:parameters
                        {:path 
                         {:customer-id "test-cust"
                          :repo-id "test-repo"
                          :build-id "test-build"}}}})
       (h/initialize-martian {:get-build {:body "test-build"
                                          :error-code :no-error}})
       (rf/dispatch [:build/load])
       (is (= 1 (count @c)))
       (is (= :get-build (-> @c first (nth 2)))))))

  (testing "clears current build"
    (is (map? (reset! app-db (db/set-build {} "test-build"))))
    (rf/dispatch-sync [:build/load])
    (is (nil? (db/logs @app-db)))))

(deftest build-maybe-load
  (testing "loads build if not in db"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (r/set-current
                       {}
                       {:parameters
                        {:path 
                         {:customer-id "test-cust"
                          :repo-id "test-repo"
                          :build-id "test-build"}}}))
       (h/initialize-martian {:get-build {:body "test-build"
                                          :error-code :no-error}})
       (rf/dispatch [:build/maybe-load])
       (is (= 1 (count @c)))
       (is (= :get-build (-> @c first (nth 2)))))))

  (testing "does not load build if already in db"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (-> {}
                          (r/set-current
                           {:parameters
                            {:path 
                             {:customer-id "test-cust"
                              :repo-id "test-repo"
                              :build-id "test-build"}}})
                          (db/set-build {:id "test-build"})))
       (h/initialize-martian {:get-build {:body "test-build"
                                          :error-code :no-error}})
       (rf/dispatch [:build/maybe-load])
       (is (empty? @c)))))

  (testing "loads build if id in db differs from route id"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (-> {}
                          (r/set-current
                           {:parameters
                            {:path 
                             {:customer-id "test-cust"
                              :repo-id "test-repo"
                              :build-id "test-build"}}})
                          (db/set-build {:id "other-build"})))
       (h/initialize-martian {:get-build {:body "test-build"
                                          :error-code :no-error}})
       (rf/dispatch [:build/maybe-load])
       (is (= 1 (count @c)))))))

(deftest build-load--success
  (testing "clears alerts"
    (is (map? (reset! app-db (db/set-alerts {} [{:type :info
                                                 :message "test notification"}]))))
    (rf/dispatch-sync [:build/load--success {:body {}}])
    (is (nil? (db/alerts @app-db))))

  (testing "clears build reload flag"
    (is (map? (reset! app-db (db/set-reloading {} #{:build}))))
    (rf/dispatch-sync [:build/load--success {:body {}}])
    (is (not (db/reloading? @app-db))))

  (testing "converts build jobs to map"
    (rf/dispatch-sync [:build/load--success {:body {:script
                                                    {:jobs [{:id "test-job"}]}}}])
    (is (= {:script
            {:jobs {"test-job" {:id "test-job"}}}}
           (db/build @app-db))))

  (testing "starts event stream when build is running")

  (testing "does not start event stream when build has finished"))

(deftest build-load--failed
  (testing "sets error"
    (rf/dispatch-sync [:build/load--failed "test-error"])
    (is (= :danger (-> (db/alerts @app-db)
                       (first)
                       :type))))
  
  (testing "clears build reload flag"
    (is (map? (reset! app-db (db/set-reloading {} #{:build}))))
    (rf/dispatch-sync [:build/load--failed {:body {}}])
    (is (not (db/reloading? @app-db)))))

(deftest build-reload
  (testing "loads build and logs from backend"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db {:route/current
                       {:parameters
                        {:path 
                         {:customer-id "test-cust"
                          :repo-id "test-repo"
                          :build-id "test-build"}}}})
       (h/initialize-martian {:get-build
                              {:body "test-build"
                               :error-code :no-error}
                              :get-build-logs
                              {:body "test-build-logs"
                               :error-code :no-error}})
       (rf/dispatch [:build/reload])
       (is (= 2 (count @c)))
       (is (= [:get-build :get-build-logs] (->> @c (map #(nth % 2))))))))

  (testing "marks reloading"
    (rf/dispatch-sync [:build/reload])
    (is (some? (db/reloading? @app-db)))))

(deftest build-download-log
  (testing "sets downloading"
    (rf/dispatch-sync [:build/download-log "test/log"])
    (is (db/downloading? @app-db)))

  (testing "sets current log path"
    (rf/dispatch-sync [:build/download-log "test/log"])
    (is (= "test/log" (db/log-path @app-db))))

  (testing "fetches builds from backend"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db {:route/current
                       {:parameters
                        {:path 
                         {:customer-id "test-cust"
                          :repo-id "test-repo"
                          :build-id "test-build"}}}})
       (h/initialize-martian {:download-log {:body "test-log"
                                             :error-code :no-error}})
       (rf/dispatch [:build/download-log "test/log/path"])
       (is (= 1 (count @c)))
       (is (= :download-log (-> @c first (nth 2)))))))

  (testing "clears alerts"
    (is (some? (reset! app-db (db/set-log-alerts {} [{:type :danger}]))))
    (rf/dispatch-sync [:build/download-log "test/log"])
    (is (empty? (db/log-alerts @app-db)))))

(deftest build-download-log--success
  (testing "sets log in db"
    (rf/dispatch-sync [:build/download-log--success {:body "test-log"}])
    (is (= "test-log" (db/current-log @app-db))))

  (testing "resets downloading? flag"
    (is (some? (reset! app-db (db/mark-downloading {}))))
    (rf/dispatch-sync [:build/download-log--success {:body "test-log"}])
    (is (not (db/downloading? @app-db)))))

(deftest build-download-log--failed
  (testing "resets downloading? flag"
    (is (some? (reset! app-db (db/mark-downloading {}))))
    (rf/dispatch-sync [:build/download-log--failed {:body {:message "test error"}}])
    (is (not (db/downloading? @app-db))))

  (testing "sets alert"
    (rf/dispatch-sync [:build/download-log--failed {:body {:message "test error"}}])
    (is (= :danger (-> @app-db
                       (db/log-alerts)
                       first
                       :type)))))

(defn- test-build []
  (->> (repeatedly 3 (comp str random-uuid))
       (zipmap [:customer-id :repo-id :build-id])))

(def build-keys [:customer-id :repo-id :build-id])
(def sid (apply juxt build-keys))

(defn- set-build-path
  "Updates the db route to match the build"
  [db build]
  (assoc-in db [:route/current :parameters :path]
            (select-keys build build-keys)))

(deftest handle-event
  (testing "ignores events for other builds"
    (let [build (test-build)
          other-build (assoc build :build-id "other-build")
          evt {:type :build/end
               :sid (sid other-build)
               :build other-build}]
      (reset! app-db (-> {}
                         (db/set-build build)
                         (set-build-path build)))
      (rf/dispatch-sync [:build/handle-event evt])
      (is (= build (db/build @app-db)))))

  (testing "updates build on `build/updated` event"
    (let [build (test-build)
          evt {:type :build/updated
               :sid (sid build)
               :build (assoc build :start-time 100)}]
      (is (some? (reset! app-db (-> {}
                                    (db/set-build build)
                                    (set-build-path build)))))
      (rf/dispatch-sync [:build/handle-event evt])
      (is (= (:build evt) (db/build @app-db)))))

  #_(testing "`build/end` event"
    (testing "updates build"
      (let [build (test-build)
            evt {:type :build/end
                 :sid (sid build)
                 :build (assoc build :status :success)}]
        (reset! app-db (-> {}
                           (db/set-build build)
                           (set-build-path build)))
        (rf/dispatch-sync [:build/handle-event evt])
        (is (= :success (:status (db/build @app-db))))))

    (testing "leaves existing script info intact"
      (let [script {:jobs {"test-job" {:status :success}}}
            build (assoc (test-build) :script script)
            evt {:type :build/end
                 :sid (sid build)
                 :build (-> build
                            (assoc :status :success)
                            (dissoc :script))}]
        (reset! app-db (-> {}
                           (db/set-build build)
                           (set-build-path build)))
        (rf/dispatch-sync [:build/handle-event evt])
        (let [updated (db/build @app-db)]
          (is (= :success (:status updated)))
          (is (= script (:script updated)))))))

  #_(letfn [(verify-script-updated [t]
            (let [build (test-build)
                  evt {:type t
                       :sid (sid build)
                       :script {:jobs {"test-job" {:status :success}}}}]
              (reset! app-db (-> {}
                                 (db/set-build build)
                                 (set-build-path build)))
              (rf/dispatch-sync [:build/handle-event evt])
              (is (= :success (-> (db/build @app-db)
                                  :script
                                  :jobs
                                  (get "test-job")
                                  :status)))))]

    (testing "updates build script on `script/start` event"
      (verify-script-updated :script/start))

    (testing "updates build script on `script/end` event"
      (verify-script-updated :script/end))

    (testing "updates jobs in script"
      (let [build (-> (test-build)
                      (assoc :script {:jobs
                                      {"test-job" {:id "test-job"
                                                   :start-time 100}}}))
            evt {:type :script/end
                 :sid (sid build)
                 :script {:jobs {"test-job" {:status :success}}}}]
        (reset! app-db (-> {}
                           (db/set-build build)
                           (set-build-path build)))
        (rf/dispatch-sync [:build/handle-event evt])
        (is (= {:id "test-job"
                :start-time 100
                :status :success}
               (-> (db/build @app-db)
                   :script
                   :jobs
                   (get "test-job")))))))

  #_(letfn [(verify-job-updated [t]
            (let [build (test-build)
                  evt {:type t
                       :sid (sid build)
                       :job {:id "test-job"
                             :key "value"}}]
              (reset! app-db (-> {}
                                 (db/set-build build)
                                 (set-build-path build)))
              (rf/dispatch-sync [:build/handle-event evt])
              (is (= "value"
                     (-> (db/build @app-db)
                         :script
                         :jobs
                         (get "test-job")
                         :key)))))]

    (testing "updates build job on `job/start` event"
      (verify-job-updated :job/start))

    (testing "updates build job on `job/end` event"
      (verify-job-updated :job/end))))

(deftest job-toggle
  (testing "marks job as expanded in db by id"
    (let [job {:id ::test-job}]
      (rf/dispatch-sync [:job/toggle job])
      (is (= #{::test-job} (db/expanded-jobs @app-db)))))

  (testing "removes expanded job from list"
    (let [job {:id ::test-job-2}]
      (rf/dispatch-sync [:job/toggle job])
      (rf/dispatch-sync [:job/toggle job])
      (is (= #{::test-job} (db/expanded-jobs @app-db))))))
