(ns monkey.ci.script.events-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.jobs :as j]
            [monkey.ci.script.events :as sut]
            [monkey.ci.spec.events :as se]
            [monkey.ci.helpers :as h]))

(deftest load-jobs
  (let [{:keys [enter] :as i} sut/load-jobs]
    (is (keyword? (:name i)))

    (testing "loads build jobs from script dir"
      (let [loaded (-> {}
                       (sut/set-build {:script
                                       {:script-dir "examples/basic-clj"}})
                       (enter)
                       (sut/get-jobs))]
        (is (not-empty loaded))
        (is (map? loaded))))

    (is (some? (remove-ns 'build)))))

(deftest execute-actions
  (let [{:keys [leave] :as i} (sut/execute-actions {:events (h/fake-events)})]
    (is (keyword? (:name i)))
    
    (testing "executes each job in the result in a new thread"
      (let [jobs [(bc/action-job "job-1" (constantly ::first))
                  (bc/action-job "job-2" (constantly ::second))]
            r (-> {}
                  (em/set-result jobs)
                  (leave))
            a (sut/get-running-actions r)]
        (is (= (count jobs) (count a)))
        (is (every? md/deferred? a))))))

(deftest events->result
  (let [{:keys [leave] :as i} sut/events->result]
    (is (keyword? (:name i)))

    (testing "`leave` overwrites result with events"
      (let [evts [{:type ::test-event}]]
        (is (= evts
               (-> {}
                   (sut/set-events evts)
                   (leave)
                   (em/get-result))))))))

(deftest routes
  (let [types [:script/initializing
               :script/start
               :script/end
               :job/queued
               :job/executed
               :job/end]
        routes (->> (sut/make-routes {})
                    (into {}))]
    (doseq [t types]
      (testing (format "handles `%s` event type" t)
        (is (contains? routes t))))))

(defn- jobs->map [jobs]
  (->> jobs
       (group-by :id)
       (mc/map-vals first)))

(deftest script-init
  (testing "fires `script/start` event with pending jobs"
    (let [jobs (jobs->map [(bc/action-job "test-job" (constantly nil))])
          r (-> {}
                (sut/set-build (h/gen-build))
                (sut/set-jobs jobs)
                (sut/script-init))]
      (is (spec/valid? ::se/event r)
          (spec/explain-str ::se/event r))
      (is (= :script/start (:type r)))
      (is (= ["test-job"]
             (->> r :jobs (map bc/job-id))))
      (is (every? (comp (partial = :pending) :status) (:jobs r))))))

(deftest script-start
  (let [jobs (jobs->map [{:id "start"
                          :status :pending}
                         {:id "next"
                          :status :pending
                          :dependencies [::start]}])
        evt {:type :script/start
             :script {:jobs (vals jobs)}}]

    (testing "with pending jobs"
      (let [r (-> {:event evt}
                  (sut/set-jobs jobs)
                  (sut/set-build (h/gen-build))
                  (sut/script-start))]
        (testing "queues jobs without dependencies"
          (let [evts (sut/get-events r)]
            (is (= 1 (count evts)))
            (is (every? (comp (partial = :job/queued) :type) evts))
            (is (spec/valid? ::se/event (first evts))
                (spec/explain-str ::se/event (first evts)))
            (is (= [(get jobs "start")]
                   (map :job evts)))))

        (testing "marks jobs enqueued in state"
          (let [s (sut/get-jobs r)]
            (is (j/queued? (get s "start")))
            (is (j/pending? (get s "next"))))))))

  (testing "returns `script/end` when no jobs"
    (let [r (sut/script-start {:event {:script {:jobs []}}})]
      (is (empty? (sut/get-queued r)))
      (is (= [:script/end] (->> (sut/get-events r)
                                (map :type))))
      (is (bc/failed? (first (sut/get-events r)))))))

(deftest script-end
  (testing "sets event in result for realization"
    (let [evt {:type :script/end
               :status :success}]
      (is (= evt (-> {:event evt}
                     (sut/script-end)
                     (em/get-result)))))))

(deftest job-queued
  (let [jobs {"first" (bc/action-job "first" (constantly nil))
              "second" (bc/container-job "second" {})}]
    (testing "returns action job for execution"
      (is (= [(get jobs "first")]
             (-> {:event {:job-id "first"}}
                 (sut/set-jobs jobs)
                 (sut/job-queued)
                 (em/get-result)))))
    
    (testing "does not execute non-action job"
      (is (nil? (-> {:event {:job-id "second"}}
                    (sut/set-jobs jobs)
                    (sut/job-queued)
                    (em/get-result)))))))

(deftest job-executed
  (testing "returns `job/end` event"
    (is (= [:job/end]
           (->> {:event
                 {:job-id "test-job"}}
                (sut/job-executed)
                (sut/get-events)
                (map :type)))))

  (testing "executes 'after' extensions"))

(deftest job-end
  (let [jobs (->> [{:id "first"
                    :status :running}
                   {:id "second"
                    :dependencies ["first"]
                    :status :pending}]
                  (group-by :id)
                  (mc/map-vals first))]
    
    (let [r (-> {:event
                 {:job-id "first"
                  :status :success}}
                (sut/set-jobs jobs)
                (sut/job-end))]
      (testing "queues pending jobs with completed dependencies"
        (let [evts (sut/get-events r)]
          (is (= [:job/queued] (map :type evts)))
          (is (= "second" (-> evts first :job-id)))))

      (testing "updates queued jobs in state"
        (is (= :queued
               (-> r
                   (sut/get-jobs)
                   (get "second")
                   :status)))))

    (testing "returns `script/end` event when no more jobs to run"
      (is (= [:script/end]
             (->> (sut/job-end {})
                  (sut/get-events)
                  (map :type)))))

    (testing "marks script as `:error` if a job has failed"
      (is (= :error
             (-> {:event
                  {:job-id "failed"
                   :status :error}}
                 (sut/set-jobs (jobs->map [{:id "failed"
                                            :status :running}]))
                 (sut/job-end)
                 (sut/get-events)
                 first
                 :status))))

    (testing "marks script as `:success` if all jobs succeeded"
      (is (= :success
             (-> {:event
                  {:job-id "success"
                   :status :success}}
                 (sut/set-jobs (jobs->map [{:id "success"
                                            :status :running}]))
                 (sut/job-end)
                 (sut/get-events)
                 first
                 :status))))

    (testing "updates job in state"
      (is (= :success (-> {:event
                           {:job-id "first"
                            :status :success}}
                          (sut/set-jobs jobs)
                          (sut/job-end)
                          (sut/get-jobs)
                          (get "first")
                          :status))))))
