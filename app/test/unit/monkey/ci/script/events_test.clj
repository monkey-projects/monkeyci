(ns monkey.ci.script.events-test
  (:require [clojure.spec.alpha :as spec]
            [clojure.test :refer [deftest is testing]]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci.build
             [api :as ba]
             [core :as bc]
             [v2 :as v2]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci
             [extensions :as ext]
             [jobs :as j]]
            [monkey.ci.script
             [core :as sc]
             [events :as sut]]
            [monkey.ci.spec.events :as se]
            [monkey.ci.test
             [extensions :as te]
             [helpers :as h]
             [mailman :as tm]]
            [monkey.mailman.core :as mmc]))

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

    (is (some? (remove-ns 'build)))

    (testing "passes initial job ctx"
      (let [init-ctx {::key ::value
                      :build ::test-build
                      :archs ::archs}
            job-ctx (atom nil)]
        (with-redefs [sc/load-jobs (fn [_ ctx]
                                     (reset! job-ctx ctx)
                                     [])]
          (is (some? (-> {}
                         (sut/set-initial-job-ctx init-ctx)
                         (enter))))
          (is (= {:build ::test-build
                  :archs ::archs}
                 @job-ctx)))))))

(deftest add-job-ctx
  (let [{:keys [enter] :as i} sut/add-job-ctx
        job (h/gen-job)
        ctx (-> {:event {:job-id (:id job)}}
                (sut/set-initial-job-ctx {::key ::value})
                (sut/set-jobs (jobs->map [job]))
                (enter)
                (emi/get-job-ctx))]
    (is (keyword? (:name i)))

    (testing "adds initial context values from state"
      (is (= ::value (::key ctx))))    
    
    (testing "adds job to job context"
      (is (= job (:job ctx))))))

(deftest with-job-ctx
  (let [{:keys [leave] :as i} sut/with-job-ctx]
    (testing "`leave` saves context in state"
      (is (= ::updated (-> {:event {:job-id "test-job"}}
                           (emi/set-job-ctx {::value ::updated})
                           (leave)
                           (sut/get-job-ctx)
                           ::value))))))

(deftest execute-action
  (let [broker (tm/test-component)
        job-ctx  {:mailman broker}
        {:keys [enter] :as i} sut/execute-action]
    (is (keyword? (:name i)))
    
    (testing "executes each job in the result in a new thread"
      (let [jobs (jobs->map [(bc/action-job "job-1" (constantly ::first))
                             (bc/action-job "job-2" (constantly ::second))])
            r (-> {:event
                   {:job-id "job-1"}}
                  (sut/set-jobs jobs)
                  (emi/set-job-ctx job-ctx)
                  (enter))
            a (sut/get-running-actions r)]
        (is (= 1 (count a)))
        (is (every? md/deferred? a))))

    (testing "ignores non-action jobs"
      (let [jobs (jobs->map [(bc/container-job "job-1" {:image "test-img"})])
            r (-> {:event
                   {:job-id "job-1"}}
                  (sut/set-jobs jobs)
                  (emi/set-job-ctx job-ctx)
                  (enter))
            a (sut/get-running-actions r)]
        (is (empty? a))))

    (testing "fires `job/end` event"
      (testing "on exception"
        (let [job (bc/action-job "failing-sync" nil)
              r (-> {:event {:job-id (:id job)}}
                    (sut/set-jobs (jobs->map [job]))
                    (emi/set-job-ctx job-ctx)
                    (enter))]
          (is (= 1 (count (sut/get-running-actions r))))
          (is (not= :timeout (h/wait-until #(contains? (->> (tm/get-posted broker) (map :type) set)
                                                       :job/end)
                                           1000)))
          (let [evt (->> (tm/get-posted broker)
                         (filter (comp (partial = :job/end) :type))
                         (first))]
            (is (= :failure (:status evt)))
            (is (string? (-> evt :result :message))))))

      (testing "on async exception"
        (tm/clear-posted! broker)
        (let [job (bc/action-job
                   "failing-async"
                   (fn [_]
                     (throw (ex-info "Test error" {}))))
              r (-> {:event {:job-id (:id job)}}
                    (sut/set-jobs (jobs->map [job]))
                    (emi/set-job-ctx job-ctx)
                    (enter))]
          (is (= 1 (count (sut/get-running-actions r))))
          (is (not= :timeout (h/wait-until #(contains? (->> (tm/get-posted broker) (map :type) set)
                                                       :job/end)
                                           1000)))
          (let [evt (->> (tm/get-posted broker)
                         (filter (comp (partial = :job/end) :type))
                         (first))]
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

(deftest add-result-to-ctx
  (let [{:keys [enter] :as i} sut/add-result-to-ctx]
    (is (keyword? (:name i)))

    (testing "`enter` adds result and status to job context result"
      (is (= {:message "ok"
              :status :success}
             (-> {:event {:result {:message "ok"}
                          :status :success}}
                 (enter)
                 (emi/get-job-ctx)
                 :job
                 :result))))))
 
(deftest handle-script-error
  (let [{:keys [error] :as i} sut/handle-script-error]
    (is (keyword? (:name i)))
    (testing "adds script/end event to result"
      (is (= [:script/end]
             (->> (error {} (ex-info "test error" {}))
                  (em/get-result)
                  (map :type)))))))

(deftest mark-canceled
  (let [{:keys [enter] :as i} sut/mark-canceled
        build-sid ["test" "build"]]
    (is (keyword? (:name i)))
    
    (testing "sets build canceled in state"
      (is (sut/build-canceled?
           (-> {:event
                {:sid build-sid}}
               (emi/set-state {:build {:sid build-sid}})
               (enter)))))

    (testing "ignores events for other builds"
      (is (not (sut/build-canceled?
                (-> {:event
                     {:sid ["other" "build"]}}
                    (emi/set-state {:build {:sid build-sid}})
                    (enter))))))))

(deftest routes
  (let [types [:script/initializing
               :script/start
               :script/end
               :job/queued
               :job/executed
               :job/end
               :build/canceled]
        routes (->> (sut/make-routes {})
                    (into {}))]
    (doseq [t types]
      (testing (format "handles `%s` event type" t)
        (is (contains? routes t))))

    (testing "`script/initializing` passes api client for loading"
      (let [fake-loader {:name ::sut/load-jobs
                         :enter (fn [ctx]
                                  (cond-> ctx
                                    (= ::test-client (-> ctx
                                                         (sut/get-initial-job-ctx)
                                                         (ba/ctx->api-client)))
                                    (sut/set-jobs {"test-job" {:id "test-job"}})))}
            r (-> (sut/make-routes {:api-client ::test-client})
                  (mmc/router)
                  (mmc/replace-interceptors [fake-loader]))]
        (is (not-empty (-> (r {:type :script/initializing})
                           (first)
                           :result
                           :jobs)))))

    (testing "`job/executed` adds result from extensions to event"
      (let [ext-id ::test-ext
            test-ext {:key ext-id
                      :after (fn [rt]
                               (ext/set-value rt ext-id (str (:value (ext/get-config rt ext-id)) " - updated")))}
            job {:id "test-job"
                 ext-id {:value "test"}
                 :status :success
                 :result {:message "test message"}}
            test-state (emi/with-state (atom {:jobs (jobs->map [job])
                                              ::sut/job-ctx {(:id job) (select-keys job [:status :result])}}))
            r (-> (sut/make-routes {:api-client ::test-client})
                  (mmc/router)
                  (mmc/replace-interceptors [test-state]))]
        (te/with-extensions
          (is (some? (ext/register! test-ext)))
          (is (= "test - updated"
                 (-> {:type :job/executed
                      :job-id (:id job)}
                     (r)
                     first
                     :result
                     first
                     :result
                     ext-id))))))))

(deftest make-job-ctx
  (testing "passes archs from config"
    (let [archs [:arch-1 :arch-2]]
      (is (= archs (-> {:archs archs}
                       (sut/make-job-ctx)
                       (v2/archs)))))))

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
      (is (every? (comp (partial = :pending) :status) (:jobs r)))))

  (testing "includes extra properties for jobs"
    (let [job (bc/container-job "extended-job"
                                {:image "test-img"
                                 :script ["test-cmd"]
                                 :ext-key :ext-value})
          r (-> {}
                (sut/set-build (h/gen-build))
                (sut/set-jobs (jobs->map [job]))
                (sut/script-init))]
      (is (spec/valid? ::se/event r)
          (spec/explain-str ::se/event r))
      (is (= "test-img" (-> r :jobs first :image)))
      (is (= :ext-value (-> r :jobs first :ext-key))))))

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

(deftest job-queued
  (let [jobs (jobs->map
              [(bc/action-job "action-job" (constantly nil))
               (bc/container-job "container-job" {})])]
    (testing "for action job, returns `nil`"
      (is (empty?
           (-> {:event
                {:job-id "action-job"}}
               (sut/set-jobs jobs)
               (sut/job-queued)))))

    (testing "for container job, returns `container/job-queued` event"
      (is (= [:container/job-queued]
             (-> {:event
                  {:job-id "container-job"}}
                 (sut/set-jobs jobs)
                 (sut/job-queued)
                 (as-> x (map :type x))))))))

(deftest job-executed
  (testing "returns `job/end` event"
    (let [r (->> {:event
                  {:job-id "test-job"
                   :status :success}}
                 (sut/job-executed))]
      (is (= [:job/end]
             (map :type r)))
      (is (= :success (-> r first :status)))))

  (testing "treats `nil` status as success"
    (is (= :success
           (-> {:event
                {:job-id "job-without-status"}}
               (sut/job-executed)
               first
               :status)))))

(deftest job-end
  (testing "when more jobs to run"
    (testing "queues pending jobs with completed dependencies"
      (let [jobs (jobs->map
                  [{:id "first"
                    :status :success}
                   {:id "second"
                    :dependencies ["first"]
                    :status :pending}])
            r (-> {:event
                   {:job-id "first"
                    :status :success}}
                  (sut/set-jobs jobs)
                  (sut/job-end))]
        (is (= [:job/queued] (map :type r)))
        (is (= "second" (-> r first :job-id)))))

    (testing "nothing if no new jobs to queue, but some have not ended"
      (let [jobs (jobs->map
                  [{:id "first"
                    :status :success}
                   {:id "second"
                    :status :initializing}
                   {:id "third"
                    :status :pending
                    :dependencies ["second"]}])
            r (-> {:event
                   {:type :job/end
                    :job-id "first"
                    :status :success}}
                  (sut/set-jobs jobs)
                  (sut/job-end))]
        (is (empty? r))))

    (testing "returns `script/end` with status `canceled` if build canceled"
      (let [r (-> {:event
                   {:type :job/end
                    :job-id "first"
                    :status :success}}
                  (sut/set-jobs (jobs->map [{:id "first" :status :success}
                                            {:id "second" :dependencies ["first"]}]))
                  (sut/set-build-canceled)
                  (sut/job-end))]
        (is (= [:script/end
                :job/skipped]
               (map :type r))))))

  (testing "when no more jobs to run"
    (testing "returns `script/end` event"
      (is (= [:script/end]
             (->> (sut/job-end {})
                  (map :type)))))

    (testing "marks script as `:error` if a job has failed"
      (is (= :error
             (-> {:event
                  {:job-id "failed"
                   :status :failure}}
                 (sut/set-jobs (jobs->map [{:id "failed"
                                            :status :failure}]))
                 (sut/job-end)
                 first
                 :status))))

    (testing "marks script as `:success` if all jobs succeeded"
      (is (= :success
             (-> {:event
                  {:job-id "success"
                   :status :success}}
                 (sut/set-jobs (jobs->map [{:id "success"
                                            :status :success}]))
                 (sut/job-end)
                 first
                 :status))))
    
    (testing "marks remaining pending jobs as skipped"
      (let [jobs (jobs->map [{:id "first"
                              :status :failure}
                             {:id "second"
                              :status :pending
                              :dependencies ["first"]}])]
        (is (= ["second"]
               (->> (-> {:event {:type :job/end
                                 :status :failure}}
                        (sut/set-jobs jobs)
                        (sut/job-end))
                    (filter (comp (partial = :job/skipped) :type))
                    (map :job-id))))))))
