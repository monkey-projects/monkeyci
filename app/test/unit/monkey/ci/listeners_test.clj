(ns monkey.ci.listeners-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [monkey.ci
             [listeners :as sut]
             [runtime :as rt]
             [storage :as st]]
            [monkey.ci.helpers :as h]))

(defn- random-sid []
  (repeatedly 3 (comp str random-uuid)))

(defn- test-build
  ([sid]
   (-> (zipmap [:customer-id :repo-id :build-id] sid)
       (assoc :status :pending
              :sid sid)))
  ([]
   (test-build (random-sid))))

(deftest update-build
  (testing "updates build entity"
    (h/with-memory-store st
      (let [sid (random-sid)
            evt {:type :build/end
                 :build (-> (test-build sid)
                            (assoc :status :success))
                 :sid sid
                 :exit 0
                 :result :success}]
        (is (map? (sut/update-build st evt)))
        (is (true? (st/build-exists? st sid)))
        (is (= :success
               (:status (st/find-build st sid)))))))

  (testing "removes unwanted fields"
    (h/with-memory-store st
      (let [sid (random-sid)
            evt {:type :build/end
                 :build (-> (test-build sid)
                            (assoc :status :success
                                   :cleanup? true))
                 :sid sid
                 :exit 0
                 :result :success}]
        (is (map? (sut/update-build st evt)))
        (let [match (st/find-build st sid)]
          (is (not (contains? match :sid)))
          (is (not (contains? match :cleanup?)))))))

  (testing "does not remove existing script info"
    (h/with-memory-store st
      (let [sid (random-sid)
            script {:jobs {"test-job" {:status :success}}}
            build (-> (test-build sid)
                      (assoc :status :success
                             :script script))
            evt {:type :build/end
                 :build (dissoc build :script)
                 :sid sid
                 :exit 0
                 :result :success}]
        (is (st/sid? (st/save-build st build)))
        (is (map? (sut/update-build st evt)))
        (let [match (st/find-build st sid)]
          (is (= script (:script match)))))))

  (testing "`build/end` calculates consumed credits"
    (h/with-memory-store st
      (let [sid (random-sid)
            build (-> (test-build sid)
                      (assoc-in [:script :jobs] {:job-1 {:id "job-1"
                                                         :start-time 100
                                                         :end-time 200
                                                         :credit-multiplier 1}}))
            handler (sut/build-update-handler st (h/fake-events))]
        (is (some? (st/save-build st build)))
        (is (some? (sut/update-build st {:type :build/end
                                         :build build})))
        (is (number? (-> (st/find-build st sid)
                         :credits)))))))

(deftest update-script
  (testing "updates script in build"
    (h/with-memory-store st
      (let [{:keys [sid] :as build} (test-build)
            stored-build (dissoc build :sid)
            script {:start-time 100
                    :status :running
                    :jobs {"test-job" {}}}
            evt {:type :script/start
                 :sid sid
                 :script script}]
        (is (st/sid? (st/save-build st stored-build)))
        (is (= sid (:sid (sut/update-script st evt))))
        (let [match (st/find-build st sid)]
          (is (= script (:script match)))))))

  (testing "returns `nil` when build not found"
    (h/with-memory-store st
      (let [{:keys [sid] :as build} (test-build)
            script {:start-time 100
                    :status :running
                    :jobs {"test-job" {}}}
            evt {:type :script/start
                 :sid sid
                 :script script}]
        (is (nil? (sut/update-script st evt))))))

  (testing "merges job info with existing"
    (h/with-memory-store st
      (let [{:keys [sid] :as build} (-> (test-build)
                                        (assoc :script
                                               {:start-time 100
                                                :status :running
                                                :jobs {"test-job" {:start-time 100
                                                                   :status :pending}}}))
            script {:start-time 100
                    :status :running
                    :jobs {"test-job" {:status :skipped
                                       :end-time 200}}}
            evt {:type :script/start
                 :sid sid
                 :script script}]
        (is (st/sid? (st/save-build st build)))
        (is (map? (sut/update-script st evt)))
        (let [match (st/find-build st sid)
              job (get-in match [:script :jobs "test-job"])]
          (is (some? job))
          (is (= :skipped (:status job)))
          (is (= 100 (:start-time job)))
          (is (= 200 (:end-time job))))))))

(deftest update-job
  (h/with-memory-store st
    (let [{:keys [sid] :as build} (test-build)
          stored-build (dissoc build :sid)]
      
      (is (st/sid? (st/save-build st stored-build)))
      
      (testing "patches build script with job info"
        (let [job {:id "test-job"
                   :start-time 120
                   :status :success}
              evt {:type :job/start
                   :sid sid
                   :job job
                   :message "Starting job"}]
          (is (= sid (:sid (sut/update-job st evt))))
          (is (= job
                 (-> (st/find-build st sid)
                     (get-in [:script :jobs "test-job"]))))))

      (testing "marks running when no status"
        (let [evt {:type :job/start
                   :sid sid
                   :job {:id "running-job"
                         :start-time 120}
                   :message "Starting job"}]
          (is (= sid (:sid (sut/update-job st evt))))
          (is (= :running
                 (-> (st/find-build st sid)
                     (get-in [:script :jobs "running-job"])
                     :status))))))))

(deftest build-update-handler
  (testing "creates a fn"
    (is (fn? (sut/build-update-handler {} nil))))

  (letfn [(verify-build-event [evt-type]
            (h/with-memory-store st
              (let [sid (random-sid)
                    build (test-build sid)
                    handler (sut/build-update-handler st (h/fake-events))]
                (handler {:type evt-type
                          :build build})
                (is (not= :timeout (h/wait-until #(st/build-exists? st sid) 1000))))))]
    
    (testing "handles `build/start`"
      (verify-build-event :build/start))

    (testing "handles `build/end`"
      (verify-build-event :build/end)))

  (letfn [(verify-script-event [evt-type]
            (h/with-memory-store st
              (let [sid (random-sid)
                    build (test-build sid)
                    _ (st/save-build st build)
                    script {:jobs {"test-job" {:status :success}}}
                    handler (sut/build-update-handler st (h/fake-events))]
                (handler {:type evt-type
                          :sid sid
                          :script script})
                (is (not= :timeout (h/wait-until (comp some? :script #(st/find-build st sid)) 1000))))))]
    
    (testing "handles `script/start`"
      (verify-script-event :script/start))

    (testing "handles `script/end`"
      (verify-script-event :script/end)))

  (letfn [(verify-job-event [evt-type]
            (h/with-memory-store st
              (let [sid (random-sid)
                    build (test-build sid)
                    job-id (random-uuid)
                    _ (st/save-build st build)
                    job {:id job-id
                         :status :success}
                    handler (sut/build-update-handler st (h/fake-events))]
                (handler {:type evt-type
                          :sid sid
                          :job job})
                (is (not= :timeout (h/wait-until (comp some?
                                                       #(get-in % [:script :jobs job-id])
                                                       #(st/find-build st sid)) 1000))))))]
    
    (testing "handles `job/start`"
      (verify-job-event :job/start))

    (testing "handles `job/updated`"
      (verify-job-event :job/updated))

    (testing "handles `job/end`"
      (verify-job-event :job/end)))  

  (testing "dispatches events in sequence"
    (let [inv (atom {})
          handled (atom 0)
          handler (fn [_ {:keys [sid] :as evt}]
                       (Thread/sleep 100)
                       (if (= :job/start (:type evt))
                         (swap! inv assoc sid [:started])
                         (swap! inv update sid conj :completed))
                       (swap! handled inc))]
      (with-redefs [sut/update-handlers
                    {:job/start handler
                     :job/end handler}]
        (let [h (sut/build-update-handler (st/make-memory-storage) (h/fake-events))]
          (h {:type :job/start
              :sid [::first]})
          (h {:type :job/start
              :sid [::second]})
          (h {:type :job/end
              :sid [::first]})
          (h {:type :job/end
              :sid [::second]})
          (is (not= :timeout (h/wait-until #(= 4 @handled) 1000)))
          (doseq [[k r] @inv]
            (is (= [:started :completed] r) (str "for id " k)))))))

  (testing "dispatches `build/updated` event"
    (h/with-memory-store st
      (let [sid (random-sid)
            events (h/fake-events)
            build (test-build sid)
            _ (st/save-build st build)
            script {:jobs {"test-job" {:status :success}}}
            handler (sut/build-update-handler st events)]
        (handler {:type :script/start
                  :sid sid
                  :script script})
        (is (not= :timeout (h/wait-until (comp some? :script #(st/find-build st sid)) 1000)))
        (let [[e] @(:recv events)]
          (is (some? e))
          (is (= :build/updated (:type e)))
          (is (= sid (:sid e)))
          (is (= script (get-in e [:build :script])))))))

  (testing "`build/updated` event has sid from `build/end`"
    (h/with-memory-store st
      (let [sid (random-sid)
            events (h/fake-events)
            build (test-build sid)
            _ (st/save-build st build)
            handler (sut/build-update-handler st events)]
        (handler {:type :build/end
                  :sid sid
                  :build (assoc build :end-time 200)})
        (is (not= :timeout (h/wait-until (comp some? :end-time #(st/find-build st sid)) 1000)))
        (let [[e] @(:recv events)]
          (is (some? e))
          (is (= :build/updated (:type e)))
          (is (= sid (:sid e))))))))

(deftest setup-runtime
  (testing "`nil` if no events configured"
    (is (nil? (rt/setup-runtime {:app-mode :server
                                 :storage ::test-storage} :listeners))))

  (testing "`nil` if no storage configured"
    (is (nil? (rt/setup-runtime {:app-mode :server
                                 :events ::test-events} :listeners))))

  (testing "returns component if storage and events configured"
    (is (some? (rt/setup-runtime {:app-mode :server
                                  :storage ::test-storage
                                  :events ::test-events}
                                 :listeners))))

  (testing "`nil` if not in server mode"
    (is (nil? (rt/setup-runtime {:app-mode :script
                                 :storage ::test-storage
                                 :events ::test-events}
                                :listeners)))))

(deftest listeners-component
  (testing "adds listeners on start"
    (let [{:keys [listeners] :as er} (h/fake-events-receiver)
          c (-> (sut/map->Listeners {:events er})
                (co/start))]
      (is (= 1 (count @listeners)))))

  (testing "removes listener on stop"
    (let [{:keys [listeners] :as er} (h/fake-events-receiver)
          c (-> (sut/map->Listeners {:events er})
                (co/start)
                (co/stop))]
      (is (empty? (-> @listeners first second)))))

  (testing "does not register listeners twice"
    (let [{:keys [listeners] :as er} (h/fake-events-receiver)
          c (-> (sut/map->Listeners {:events er})
                (co/start)
                (co/start))]
      (is (= 1 (-> @listeners first second count))))))
