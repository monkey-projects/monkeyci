(ns monkey.ci.gui.test.build.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.customer.db :as cdb]
            [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.build.events :as sut]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(defn- build-sid []
  (repeatedly 3 (comp str random-uuid)))

(defn- gen-build []
  (zipmap [:customer-id :repo-id :build-id] (build-sid)))

(defn- build->params [build]
  {:parameters
   {:path (select-keys build [:customer-id :repo-id :build-id])}})

(def build-keys [:customer-id :repo-id :build-id])
(def sid (apply juxt build-keys))

(defn- set-build-path
  "Updates the db route to match the build"
  [db build]
  (r/set-current db (build->params build)))

(deftest build-init
  (letfn [(mock-handlers []
            (rf/reg-event-db :build/load (constantly nil))
            (rf/reg-event-db :customer/maybe-load (constantly nil))
            (rf/reg-event-db :event-stream/start (constantly nil))
            (rf/reg-event-db :route/on-page-leave (constantly nil)))]

    (let [db (set-build-path {} (gen-build))]
      (is (some? (reset! app-db db)))
      
      (testing "when not initialized"
        (testing "dispatches load event"
          (rft/run-test-sync
           (let [c (atom [])]
             (mock-handlers)
             (rf/reg-event-db :build/load (fn [_ evt] (swap! c conj evt)))
             (rf/dispatch [:build/init])
             (is (= 1 (count @c))))))

        (testing "dispatches customer load event"
          (rft/run-test-sync
           (let [c (atom [])]
             (mock-handlers)
             (rf/reg-event-db :customer/maybe-load (fn [_ evt] (swap! c conj evt)))
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
           (reset! app-db {})
           (rf/dispatch [:build/init])
           (is (true? (lo/initialized? @app-db (db/get-id db)))))))

      (testing "does nothing when initialized"
        (reset! app-db (lo/set-initialized {} (db/get-id db)))
        (rft/run-test-sync
         (mock-handlers)
         (let [c (atom [])
               h (fn [_ evt] (swap! c conj evt))]
           (rf/reg-event-db :build/load h)
           (rf/reg-event-db :event-stream/start h)
           (rf/reg-event-db :route/on-page-leave h)
           (rf/dispatch [:build/init])
           (is (empty? @c))))))))

(deftest build-load
  (testing "resets alerts"
    (is (some? (reset! app-db (db/set-alerts {} [{:type :info :message "test alert"}]))))
    (rf/dispatch-sync [:build/load])
    (is (empty? (db/get-alerts @app-db))))

  (testing "fetches builds from backend"
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
       (rf/dispatch [:build/load])
       (is (= 1 (count @c)))
       (is (= :get-build (-> @c first (nth 2)))))))

  (testing "does not clear build if it's the same path"
    (let [build (gen-build)]
      (is (some? (reset! app-db (-> {}
                                    (r/set-current
                                     {:parameters
                                      {:path build}})
                                    (db/set-build build)))))
      (rf/dispatch-sync [:build/load])
      (is (= build (db/get-build @app-db)))))

  (testing "clears current build if path differs"
    (is (some? (reset! app-db (-> {}
                                  (r/set-current
                                   {:parameters
                                    {:path (gen-build)}})
                                  (db/set-build (gen-build))))))
    (rf/dispatch-sync [:build/load])
    (is (nil? (db/get-build @app-db)))))

(deftest build-load--success
  (testing "converts build jobs to map"
    (rf/dispatch-sync [:build/load--success {:body {:script
                                                    {:jobs [{:id "test-job"}]}}}])
    (is (= {:script
            {:jobs {"test-job" {:id "test-job"}}}}
           (db/get-build @app-db))))

  (testing "adds customer and repo id from route"
    (let [[_ _ build-id :as sid] (build-sid)]
      (is (some? (reset! app-db (r/set-current
                                 {}
                                 {:parameters
                                  {:path (zipmap [:customer-id :repo-id :build-id] sid)}}))))
      (rf/dispatch-sync [:build/load--success {:body {:build-id build-id}}])
      (is (= sid
             (-> (db/get-build @app-db)
                 (select-keys [:customer-id :repo-id :build-id])
                 (vals))))))

  (testing "starts event stream when build is running")

  (testing "does not start event stream when build has finished"))

(deftest build-load--failed
  (let [db (set-build-path {} (gen-build))]
    (is (some? (reset! app-db db)))
    
    (testing "sets error"
      (rf/dispatch-sync [:build/load--failed "test-error"])
      (is (= :danger (-> (db/get-alerts @app-db)
                         (first)
                         :type))))
    
    (testing "clears build loading flag"
      (is (map? (reset! app-db (lo/set-loading db (db/get-id db)))))
      (rf/dispatch-sync [:build/load--failed {:body {}}])
      (is (not (lo/loading? @app-db (db/get-id db)))))))

(deftest build-reload
  (testing "loads build from backend"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)
           build (gen-build)]
       (reset! app-db (set-build-path {} build))
       (h/initialize-martian {:get-build
                              {:body "test-build"
                               :error-code :no-error}})
       (rf/dispatch [:build/reload])
       (is (= 1 (count @c)))
       (is (= [:get-build] (->> @c (map #(nth % 2))))))))

  (testing "marks loading"
    (rf/dispatch-sync [:build/reload])
    (is (some? (lo/loading? @app-db db/id)))))

(defn- test-build []
  (->> (repeatedly 3 (comp str random-uuid))
       (zipmap [:customer-id :repo-id :build-id])))

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
      (is (= build (db/get-build @app-db)))))

  (testing "updates build on `build/updated` event"
    (let [build (test-build)
          evt {:type :build/updated
               :sid (sid build)
               :build (assoc build :start-time 100)}]
      (is (some? (reset! app-db (-> {}
                                    (db/set-build build)
                                    (set-build-path build)))))
      (rf/dispatch-sync [:build/handle-event evt])
      (is (= (:build evt) (db/get-build @app-db))))))

(deftest build-cancel
  (rft/run-test-sync
   (let [c (h/catch-fx :martian.re-frame/request)
         build (gen-build)]
     (reset! app-db (set-build-path {} build))
     (h/initialize-martian {:get-build {:body "test-build"
                                        :error-code :no-error}})
     (rf/dispatch [:build/cancel])

     (testing "invokes cancel-build endpoint"
       (is (= 1 (count @c)))
       (is (= :cancel-build (-> @c first (nth 2)))))

     (testing "marks build canceling"
       (is (db/canceling? @app-db))))))

(deftest build-cancel--success
  (let [build (gen-build)]
    (is (some? (reset! app-db (-> {}
                                  (set-build-path build)
                                  (db/mark-canceling)))))
    (rf/dispatch-sync [:build/cancel--success])
    
    (testing "unmarks canceling"
      (is (not (db/canceling? @app-db))))

    (testing "sets alert"
      (is (not-empty (db/get-alerts @app-db))))))

(deftest build-cancel--failed
  (let [build (gen-build)]
    (is (some? (reset! app-db (-> {}
                                  (set-build-path build)
                                  (db/mark-canceling)))))
    (rf/dispatch-sync [:build/cancel--failed "test error"])
    
    (testing "unmarks canceling"
      (is (not (db/canceling? @app-db))))

    (testing "sets alert"
      (is (not-empty (db/get-alerts @app-db))))))

(deftest build-retry
  (rft/run-test-sync
   (let [c (h/catch-fx :martian.re-frame/request)
         build (gen-build)]
     (reset! app-db (set-build-path {} build))
     (h/initialize-martian {:get-build {:body "test-build"
                                        :error-code :no-error}})
     (rf/dispatch [:build/retry])

     (testing "invokes retry-build endpoint"
       (is (= 1 (count @c)))
       (is (= :retry-build (-> @c first (nth 2)))))

     (testing "marks build retrying"
       (is (db/retrying? @app-db))))))

(deftest build-retry--success
  (let [build (gen-build)]
    (is (some? (reset! app-db (-> {}
                                  (set-build-path build)
                                  (db/mark-retrying)))))
    (rf/dispatch-sync [:build/retry--success])
    
    (testing "unmarks retrying"
      (is (not (db/retrying? @app-db))))

    (testing "sets alert"
      (is (not-empty (db/get-alerts @app-db))))))

(deftest build-retry--failed
  (let [build (gen-build)]
    (is (some? (reset! app-db (-> {}
                                  (set-build-path build)
                                  (db/mark-retrying)))))
    (rf/dispatch-sync [:build/retry--failed "test error"])
    
    (testing "unmarks retrying"
      (is (not (db/retrying? @app-db))))

    (testing "sets alert"
      (is (not-empty (db/get-alerts @app-db))))))
