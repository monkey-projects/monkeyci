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
    (is (nil? (db/build @app-db)))))

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
  (testing "loads build from backend"
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
                               :error-code :no-error}})
       (rf/dispatch [:build/reload])
       (is (= 1 (count @c)))
       (is (= [:get-build] (->> @c (map #(nth % 2))))))))

  (testing "marks reloading"
    (rf/dispatch-sync [:build/reload])
    (is (some? (db/reloading? @app-db)))))

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
      (is (= (:build evt) (db/build @app-db))))))

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
