(ns monkey.ci.script.events-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as ca]
            [medley.core :as mc]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.jobs :as cj]
            [monkey.ci.script
             [events :as sut]
             [helpers :as h]
             [load :as l]]
            [monkey.mailman.core-async :as mmca]))

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
                                       {:script-dir "dev-resources/test"}})
                       (enter)
                       (sut/get-jobs))]
        (is (not-empty loaded))
        (is (map? loaded))
        (is (= 1 (count loaded)))))

    #?(:bb
       ;; nil in bb because ns is not really loaded?
       (is (nil? (remove-ns 'build)))
       :clj
       (is (some? (remove-ns 'build))))

    (testing "passes initial job ctx"
      (let [init-ctx {::key ::value
                      :build ::test-build
                      :archs ::archs}
            job-ctx (atom nil)]
        (with-redefs [l/load-jobs (fn [_ ctx]
                                    (reset! job-ctx ctx)
                                    [])]
          (is (some? (-> {}
                         (sut/set-initial-job-ctx init-ctx)
                         (enter))))
          (is (= {:build ::test-build
                  :archs ::archs}
                 @job-ctx)))))

    (testing "applies job filter"
      (let [job-ctx (atom nil)]
        (with-redefs [l/load-jobs (fn [_ _]
                                    [{:id "first"}
                                     {:id "second"}])]
          (is (= ["first"]
                 (-> {}
                     (emi/set-state {:filter ["first"]})
                     (enter)
                     (sut/get-jobs)
                     (keys)))))))))

(deftest add-job-ctx
  (let [{:keys [enter] :as i} sut/add-job-ctx
        job {:id "test-job"
             :type :container}
        other-job {:id "other-job"
                   :type :container}
        ctx (-> {:event {:job-id (:id job)}}
                (sut/set-initial-job-ctx {::key ::value})
                (sut/set-jobs (jobs->map [job other-job]))
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
  (let [broker (mmca/core-async-broker)
        job-ctx {:mailman broker}
        {:keys [enter] :as i} sut/execute-action]
    (is (keyword? (:name i)))
    
    (testing "executes job from the context in a new thread"
      (let [exec (ca/chan 1)
            action-fn (fn [v]
                        (fn [_ctx]
                          (if (ca/>!! exec v)
                            bc/success
                            bc/failure)))
            jobs (jobs->map [(bc/action-job "job-1" (action-fn ::first))
                             (bc/action-job "job-2" (action-fn ::second))])
            r (-> {:event
                   {:job-id "job-1"}}
                  (sut/set-jobs jobs)
                  (emi/set-job-ctx (assoc job-ctx :job (get jobs "job-1")))
                  (enter))
            a (sut/get-running-actions r)]
        (is (= 1 (count a)))
        (is (some? a) "contains thread channels")
        (is (= ::first (first (ca/alts!! [(ca/timeout 200)
                                          exec]))))))

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
      (testing "on config exception"
        (let [job (bc/action-job "invalid-job" nil)
              r (-> {:event {:job-id (:id job)}}
                    (sut/set-jobs (jobs->map [job]))
                    (emi/set-job-ctx (assoc job-ctx :job job))
                    (enter))]
          (is (= 1 (count (sut/get-running-actions r))))
          (let [evt (h/wait-for-evt broker (comp (partial = :job/end) :type))]
            (is (some? evt))
            (is (= :failure (:status evt)))
            (is (contains? (-> evt :result) :message)))))

      (testing "on action exception"
        (let [job (bc/action-job
                   "failing-action"
                   (fn [_]
                     (throw (ex-info "Test error" {}))))
              r (-> {:event {:job-id (:id job)}}
                    (sut/set-jobs (jobs->map [job]))
                    (emi/set-job-ctx (assoc job-ctx :job job))
                    (enter))]
          (is (= 1 (count (sut/get-running-actions r))))
          (let [evt (h/wait-for-evt broker (comp (partial = :job/end) :type))]
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
        (is (cj/queued? (-> (sut/get-jobs r)
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
                  (emi/get-result)
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

(deftest add-job-retriever
  (let [s (atom {:jobs {"test-job" ::test-job}})
        {:keys [enter] :as i} (sut/add-job-retriever s)]
    (is (keyword? (:name i)))
    
    (testing "adds job retriever api fn to to job context"
      (let [f (-> {}
                  (enter)
                  (emi/get-job-ctx)
                  (get-in [:api :jobs]))]
        (is (fn? f))
        (is (= ::test-job (f "test-job")))))))

(deftest update-job-init
  (let [{:keys [leave] :as i} sut/update-job-init]
    (is (keyword? (:name i)))
    
    (testing "`leave` adds agent details to job"
      (let [r (-> {:event
                   {:job-id "test-job"
                    :agent
                    {:address "test-addr"
                     :ports {10000 8080}}}}
                  (sut/set-jobs {"test-job"
                                 {:type :container}})
                  (leave))]
        (is (= "test-addr"
               (-> r
                   (sut/get-jobs)
                   (get "test-job")
                   :agent
                   :address)))))))
