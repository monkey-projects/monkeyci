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
            [monkey.ci.helpers :as h]
            [monkey.ci.test.mailman :as tm]))

(defn- jobs->map [jobs]
  (->> jobs
       (group-by :id)
       (mc/map-vals first)))

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

(deftest execute-action
  (let [broker (tm/test-component)
        {:keys [enter] :as i} (sut/execute-action {:events (h/fake-events)
                                                   :mailman broker})]
    (is (keyword? (:name i)))
    
    (testing "executes each job in the result in a new thread"
      (let [jobs (jobs->map [(bc/action-job "job-1" (constantly ::first))
                             (bc/action-job "job-2" (constantly ::second))])
            r (-> {:event
                   {:job-id "job-1"}}
                  (sut/set-jobs jobs)
                  (enter))
            a (sut/get-running-actions r)]
        (is (= 1 (count a)))
        (is (every? md/deferred? a))))

    (testing "does not execute non-action job"
      (is (empty? (-> {:event {:job-id "non-action"}}
                      (sut/set-jobs (jobs->map [(bc/container-job "non-action" {})]))
                      (enter)
                      (sut/get-running-actions)))))

    (testing "fires `job/end` event"
      (testing "on exception"
        (let [job (bc/action-job "failing-sync" nil)
              r (-> {:event {:job-id (:id job)}}
                    (sut/set-jobs (jobs->map [job]))
                    (enter))]
          (is (= 1 (count (sut/get-running-actions r))))
          (is (not= :timeout (h/wait-until #(not-empty (tm/get-posted broker)) 1000)))
          (let [evts (tm/get-posted broker)]
            (is (= [:job/end] (map :type evts)))
            (is (= :failure (-> evts first :status)))
            (is (string? (-> evts first :result :message))))))

      (testing "on async exception"
        (let [job (bc/action-job
                   "failing-async"
                   (fn [_]
                     (throw (ex-info "Test error" {}))))
              r (-> {:event {:job-id (:id job)}}
                    (sut/set-jobs (jobs->map [job]))
                    (enter))]
          (is (= 1 (count (sut/get-running-actions r))))
          (is (not= :timeout (h/wait-until #(= 2 (count (tm/get-posted broker))) 1000)))
          (let [evt (last (tm/get-posted broker))]
            (is (= :job/end (:type evt)))
            (is (= :failure (:status evt)))
            (is (= "Test error" (get-in evt [:result :message])))))))))

(deftest enqueue-jobs
  (let [{:keys [leave] :as i} sut/enqueue-jobs]
    (is (keyword? (:name i)))
    
    (testing "marks jobs enqueued in state"
      (let [job-id "test-job"
            r (-> {:result
                   [{:type :job/queued
                     :job-id job-id}]}
                  (sut/set-jobs (jobs->map
                                 [{:id job-id
                                   :status :pending}]))
                  (leave))]
        (is (j/queued? (-> (sut/get-jobs r)
                           (get job-id))))))))

(deftest set-job-result
  (let [{:keys [enter] :as i} sut/set-job-result
        jobs (jobs->map [{:id "test-job"
                          :status :running}])]
    (testing "updates job in state"
      (is (= :success (-> {:event
                           {:job-id "test-job"
                            :status :success}}
                          (sut/set-jobs jobs)
                          (enter)
                          (sut/get-jobs)
                          (get "test-job")
                          :status))))))

(deftest handle-script-error
  (let [{:keys [error] :as i} sut/handle-script-error]
    (is (keyword? (:name i)))
    (testing "adds script/end event to result"
      (is (= [:script/end]
             (->> (error {} (ex-info "test error" {}))
                  (em/get-result)
                  (map :type)))))))

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
          (is (= 1 (count r)))
          (is (every? (comp (partial = :job/queued) :type) r))
          (is (spec/valid? ::se/event (first r))
              (spec/explain-str ::se/event (first r)))
          (is (= [(get jobs "start")]
                 (map :job r)))))))

  (testing "returns `script/end` when no jobs"
    (let [r (sut/script-start {:event {:script {:jobs []}}})]
      (is (= [:script/end] (map :type r)))
      (is (bc/failed? (first r))))))

(deftest script-end
  (testing "sets event in result for realization"
    (let [evt {:type :script/end
               :status :success}]
      (is (= evt (-> {:event evt}
                     (sut/script-end)))))))

(deftest job-executed
  (testing "returns `job/end` event"
    (let [r (->> {:event
                 {:job-id "test-job"
                  :status :success}}
                (sut/job-executed))]
      (is (= [:job/end]
             (map :type r)))
      (is (= :success (-> r first :status)))))

  (testing "executes 'after' extensions"))

(deftest job-end
  (let [jobs (jobs->map
              [{:id "first"
                :status :success}
               {:id "second"
                :dependencies ["first"]
                :status :pending}])]
    
    (let [r (-> {:event
                 {:job-id "first"
                  :status :success}}
                (sut/set-jobs jobs)
                (sut/job-end))]
      (testing "queues pending jobs with completed dependencies"
        (is (= [:job/queued] (map :type r)))
        (is (= "second" (-> r first :job-id)))))

    (testing "when no more jobs to run"
      (testing "returns `script/end` event"
        (is (= [:script/end]
               (->> (sut/job-end {})
                    (map :type)))))

      (testing "marks script as `:error` if a job has failed"
        (is (= :error
               (-> {:event
                    {:job-id "failed"
                     :status :error}}
                   (sut/set-jobs (jobs->map [{:id "failed"
                                              :status :error}]))
                   (sut/job-end)
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
                   first
                   :status))))

      (testing "marks remaining pending jobs as skipped"
        (let [jobs (jobs->map [{:id "first"
                                :status :error}
                               {:id "second"
                                :status :pending
                                :dependencies ["first"]}])]
          (is (= ["second"]
                 (->> (-> {:event {:type :job/end
                                   :status :error}}
                          (sut/set-jobs jobs)
                          (sut/job-end))
                      (filter (comp (partial = :job/skipped) :type))
                      (map :job-id)))))))))
