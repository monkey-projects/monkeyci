(ns monkey.ci.gui.test.build.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.customer.db :as cdb]
            [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.build.events :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

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
           (db/build @app-db)))))

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
    (is (some? (db/reloading? @app-db))))

  (testing "sets reload time"
    (rf/reg-cofx :time/now (fn [cofx]
                             (assoc cofx :time/now 444)))
    (rf/dispatch-sync [:build/reload])
    (is (= 444 (db/last-reload-time @app-db)))))

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

  (testing "`build/end` event"
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

  (letfn [(verify-script-updated [t]
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

  (letfn [(verify-job-updated [t]
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
