(ns monkey.ci.listeners-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [monkey.ci
             [listeners :as sut]
             [runtime :as rt]
             [storage :as st]
             [time :as t]]
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

(deftest handle-event
  (h/with-memory-store st
    (let [events (h/fake-events)
          sid (random-sid)
          build (test-build sid)
          time (t/now)
          handle (fn [part-evt]
                   (sut/handle-event
                    (merge {:sid sid :time time} part-evt)
                    st events))]
      
      (testing "`build/initializing` upserts build"
        (is (some? (handle {:type :build/initializing
                            :build build})))
        (is (= :initializing (-> (st/find-build st sid)
                                 :status))))

      (testing "`build/pending` upserts build"
        (is (some? (handle {:type :build/pending
                            :build build})))
        (is (= :pending (-> (st/find-build st sid)
                            :status))))

      (testing "`build/start` sets start time and credit multiplier"
        (is (some? (handle {:type :build/start
                            :credit-multiplier 2})))
        (is (= {:start-time time
                :credit-multiplier 2
                :status :running}
               (-> (st/find-build st sid)
                   (select-keys [:start-time :credit-multiplier :status])))))

      (testing "`build/end`"
        (is (some? (handle {:type :build/end
                            :status :success})))
        
        (testing "sets end time and status"
          (is (= {:end-time time
                  :status :success}
                 (-> (st/find-build st sid)
                     (select-keys [:end-time :status])))))
        
        (testing "`build/end` calculates consumed credits"
          (let [build (-> build
                          (assoc-in [:script :jobs] {:job-1 {:id "job-1"
                                                             :start-time 100
                                                             :end-time 200
                                                             :credit-multiplier 1}}))]
            (is (some? (st/save-build st build)))
            (is (some? (handle {:type :build/end})))
            (is (number? (-> (st/find-build st sid)
                             :credits))))))

      (testing "`script/initializing` sets script dir in build"
        (is (some? (handle {:type :script/initializing
                            :script-dir "test/dir"})))
        (is (= "test/dir"
               (-> (st/find-build st sid)
                   :script
                   :script-dir))))


      (let [job-id (str (random-uuid))]
        (testing "`script/start` saves job configs"
          (let [job {:id job-id
                     :status :pending}]
            (is (some? (handle {:type :script/start
                                :jobs [job]})))
            (is (= {job-id job}
                   (-> (st/find-build st sid)
                       :script
                       :jobs)))))

        (testing "`script/end` saves status"
          (is (some? (handle {:type :script/end
                              :status :success})))
          (is (= :success
                 (-> (st/find-build st sid)
                     :script
                     :status))))

        (testing "`job/initializing`"
          (is (some? (handle {:type :job/initializing
                              :job-id job-id
                              :credit-multiplier 2})))
          (let [upd (-> (st/find-build st sid)
                        :script
                        :jobs
                        (get job-id))]

            (testing "updates job status"
              (is (= :initializing (:status upd))))

            (testing "sets credit multiplier"
              (is (= 2 (:credit-multiplier upd))))))

        (testing "`job/start`"
          (is (some? (handle {:type :job/start
                              :job-id job-id})))
          (let [upd (-> (st/find-build st sid)
                        (get-in [:script :jobs job-id]))]
            
            (testing "sets start time"
              (is (= time (:start-time upd))))

            (testing "sets status to `running`"
              (is (= :running (:status upd))))))

        (testing "`job/end`"
          (is (some? (handle {:type :job/end
                              :job-id job-id
                              :status :success
                              :result {:message "test result"}})))
          (let [upd (-> (st/find-build st sid)
                        (get-in [:script :jobs job-id]))]
            
            (testing "sets end time"
              (is (= time (:end-time upd))))

            (testing "sets status"
              (is (= :success (:status upd))))

            (testing "sets result"
              (is (= {:message "test result"} (:result upd)))))))

      (testing "dispatches `build/updated` event"
        (let [evt (->> (h/received-events events)
                       (h/first-event-by-type :build/updated))]
          (is (some? evt))
          (is (= sid (:sid evt))))))))

(deftest build-update-handler
  (testing "creates a fn"
    (is (fn? (sut/build-update-handler {} nil))))

  (testing "consumes and handles events async"
    (h/with-memory-store st
      (let [sid (random-sid)
            build (test-build sid)
            handler (sut/build-update-handler st (h/fake-events))]
        (handler {:type :build/initializing
                  :build build})
        (is (not= :timeout (h/wait-until #(st/build-exists? st sid) 1000))))))

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
            (is (= [:started :completed] r) (str "for id " k))))))))

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
