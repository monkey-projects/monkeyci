(ns monkey.ci.listeners-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [monkey.ci
             [build :as b]
             [cuid :as cuid]
             [listeners :as sut]
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

        (testing "does not overwrite message if none in event"
          (is (some? (st/save-build st (assoc build :message "existing message"))))
          (is (some? (handle {:type :build/end
                              :status :success})))
          (is (= "existing message" (-> (st/find-build st sid)
                                        :message))))
        
        (let [build (-> build
                        (assoc-in [:script :jobs] {:job-1 {:id "job-1"
                                                           :start-time 100
                                                           :end-time 200
                                                           :credit-multiplier 1}}))
              cred (-> (h/gen-cust-credit)
                       (assoc :customer-id (:customer-id build)
                              :type :user
                              :amount 100))]
          (is (some? (st/save-build st build)))
          (is (some? (st/save-customer-credit st cred)))
          (is (some? (handle {:type :build/end})))
          
          (testing "calculates consumed credits"
            (is (number? (-> (st/find-build st sid)
                             :credits))))

          (testing "creates credit consumption for available credit"
            (let [[m :as cc] (st/list-customer-credit-consumptions st (:customer-id build))]
              (is (= 1 (count cc)))
              (is (pos? (:amount m)))
              (is (= (:id cred) (:credit-id m)))
              (is (= (:build-id build) (:build-id m)))))

          (testing "when no available credit, assigns consumption to most recent credit")))

      (testing "`build/canceled`"
        (let [build (-> (test-build (concat (take 2 sid) [(cuid/random-cuid)]))
                        (assoc-in [:script :jobs "job-2"] {:id "job-2"
                                                           :start-time 100
                                                           :end-time 200
                                                           :credit-multiplier 1}))
              sid (b/sid build)]
          (is (some? (st/save-build st build)))
          (is (some? (handle {:type :build/canceled
                              :sid sid})))
          
          (testing "marks build as canceled"
            (is (= :canceled (-> (st/find-build st sid)
                                 :status))))

          (testing "calculates consumed credits"
            (is (number? (-> (st/find-build st sid)
                             :credits))))

          (testing "creates credit consumption for available credit"
            (let [cc (st/list-customer-credit-consumptions st (:customer-id build))
                  m (->> cc
                         (filter (comp (partial = (:build-id build)) :build-id))
                         (first))]
              (is (some? m))
              (is (pos? (:amount m)))))))

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

        (testing "`script/end`"
          (testing "saves status"
            (is (some? (handle {:type :script/end
                                :status :success})))
            (is (= :success
                   (-> (st/find-build st sid)
                       :script
                       :status))))

          (testing "sets build message if any"
            (is (some? (handle {:type :script/end
                                :status :failure
                                :message "test error"})))
            (is (= "test error" (-> (st/find-build st sid)
                                    :message)))))

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
                              :job-id job-id
                              :credit-multiplier 3})))
          (let [upd (-> (st/find-build st sid)
                        (get-in [:script :jobs job-id]))]
            
            (testing "sets start time"
              (is (= time (:start-time upd))))

            (testing "sets status to `running`"
              (is (= :running (:status upd))))

            (testing "sets `credit-multiplier` if specified"
              (is (= 3 (:credit-multiplier upd))))))

        (testing "`job/skipped`"
          (is (some? (handle {:type :job/skipped
                              :job-id job-id})))
          (let [upd (-> (st/find-build st sid)
                        (get-in [:script :jobs job-id]))]
            
            (testing "sets status to `skipped`"
              (is (= :skipped (:status upd))))))

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
