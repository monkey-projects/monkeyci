(ns monkey.ci.jobs-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [manifold.deferred :as md]
            [monkey.ci.build.core :as bc]
            [monkey.ci
             [artifacts :as art]
             [cache :as cache]
             [containers :as co]
             [credits :as cr]
             [edn :as edn]
             [jobs :as sut]]
            [monkey.ci.spec.events :as se]
            [monkey.ci.helpers :as h]))

(defn dummy-job
  ([id & [opts]]
   (bc/action-job id (constantly nil) opts))
  ([]
   (dummy-job ::test-job)))

(deftest next-jobs
  (testing "returns starting jobs if all are pending"
    (let [[a :as jobs] [(dummy-job ::root)
                        (dummy-job ::child {:dependencies [::root]})]]
      (is (= [a] (sut/next-jobs jobs)))))

  (testing "returns jobs that have succesful dependencies"
    (let [[_ b :as jobs] [(dummy-job ::root {:status :success})
                          (dummy-job ::child {:dependencies [::root]})]]
      (is (= [b] (sut/next-jobs jobs)))))

  (testing "does not return jobs that have failed dependencies"
    (let [[_ _ c :as jobs] [(dummy-job ::root {:status :success})
                            (dummy-job ::other-root {:status :failure})
                            (dummy-job ::child {:dependencies [::root]})
                            (dummy-job ::other-child {:dependencies [::other-root]})]]
      (is (= [c] (sut/next-jobs jobs)))))

  (testing "returns jobs that have multiple succesful dependencies"
    (let [[_ _ c :as jobs] [(dummy-job ::root {:status :success})
                            (dummy-job ::other-root {:status :success})
                            (dummy-job ::child {:dependencies [::root ::other-root]})]]
      (is (= [c] (sut/next-jobs jobs))))))

(def test-job (dummy-job ::recursive-job))

(deftest resolve-jobs
  (testing "returns job as vector"
    (let [job (dummy-job ::test-job)]
      (is (= [job] (sut/resolve-jobs job {})))))

  (testing "invokes fn to return job"
    (let [job (dummy-job ::indirect-job)]
      (is (= [job] (sut/resolve-jobs (constantly job) {})))))

  (testing "fn can return multiple jobs"
    (let [jobs (repeatedly 5 dummy-job)]
      (is (= jobs (sut/resolve-jobs (constantly jobs) {})))))

  (testing "recurses into job"
    (let [job (dummy-job ::recursive-job)]
      (is (= [job] (sut/resolve-jobs (constantly (constantly job)) {})))))

  (testing "returns job fn as action job"
    (let [job (with-meta (constantly ::ok) {:job true :job/id "test-job"})
          [r] (sut/resolve-jobs job {})]
      (is (bc/action-job? r))
      (is (= job (:action r)))
      (is (= "test-job" (bc/job-id job)))))

  (testing "resolves var"
    (is (= [test-job] (sut/resolve-jobs #'test-job {}))))

  (testing "resolves `nil` to empty"
    (is (empty? (sut/resolve-jobs nil {}))))

  (testing "resolves vector into multiple jobs"
    (let [jobs (repeatedly 2 dummy-job)
          v (mapv constantly jobs)]
      (is (= jobs (sut/resolve-jobs jobs {})))))

  (testing "resolves combination of vector and job"
    (let [jobs [(constantly (dummy-job))
                (dummy-job)]
          r (sut/resolve-jobs jobs {})]
      (is (= 2 (count r)))
      (is (every? bc/action-job? r))))

  (testing "resolves combination of vector and fn"
    (let [jobs [(constantly (dummy-job))
                [(dummy-job)]]
          r (sut/resolve-jobs jobs {})]
      (is (= 2 (count r)))
      (is (every? bc/action-job? r))))

  (testing "pipelines"
    (testing "returns jobs from pipeline vector"
      (let [[a b :as jobs] (repeatedly 2 dummy-job)]
        (is (= jobs (sut/resolve-jobs [(bc/pipeline {:jobs [a]})
                                       (bc/pipeline {:jobs [b]})]
                                      {})))))

    (testing "returns jobs from single pipeline"
      (let [job (dummy-job)
            p (bc/pipeline {:jobs [job]})]
        (is (= [job] (sut/resolve-jobs p {})))))
    
    (testing "makes each job dependent on the previous"
      (let [[a b :as jobs] [{:id ::first
                             :action (constantly ::first)}
                            {:id ::second
                             :action (constantly ::second)}]
            p (sut/resolve-jobs (bc/pipeline {:jobs jobs}) {})]
        (is (= [::first] (-> p second :dependencies)))))

    (testing "adds pipeline name as label"
      (is (= "test-pipeline" (-> {:jobs [(dummy-job)]
                                  :name "test-pipeline"}
                                 (bc/pipeline)
                                 (sut/resolve-jobs {})
                                 first
                                 sut/labels
                                 (get "pipeline")))))

    (testing "converts functions that return legacy actions to jobs"
      (is (bc/action-job? (-> (constantly {:action (constantly bc/success)
                                           :name "legacy-step"})
                              (sut/resolve-jobs {})
                              first))))

    (testing "converts pipelines with functions that return legacy actions to jobs"
      (is (bc/action-job? (-> {:jobs [(constantly {:action (constantly bc/success)
                                                   :name "legacy-step"})]
                               :name "test-pipeline"}
                              (bc/pipeline)
                              (sut/resolve-jobs {})
                              first))))))

(deftest action-job
  (let [job (bc/action-job ::test-job (constantly bc/success))
        ctx {:events (h/fake-events)
             :containers ::test-containers}]
    (testing "is a job"
      (is (sut/job? job)))
    
    (testing "executes action"
      (is (bc/success? @(sut/execute! job ctx))))

    (testing "only receives job, build and api in context"
      (is (bc/success? @(sut/execute! (bc/action-job ::test-job #(when (:containers %) bc/failure))
                                      ctx))))

    (testing "rewrites work dir to absolute path against checkout dir"
      (let [job (bc/action-job ::wd-job
                               (fn [ctx]
                                 (assoc bc/success :wd (bc/work-dir ctx)))
                               {:work-dir "sub/dir"})]
        (is (= "/checkout/sub/dir"
               (-> (sut/execute! job (assoc ctx
                                            :build {:checkout-dir "/checkout"}
                                            :job job))
                   (deref)
                   :wd)))))
    
    (testing "restores/saves cache if configured"
      (let [saved (atom false)]
        (with-redefs [cache/save-caches
                      (fn [rt]
                        (reset! saved true)
                        rt)
                      cache/restore-caches
                      (fn [rt]
                        (->> (get-in rt [:job :caches])
                             (mapv :id)))]
          (let [job (bc/action-job ::job-with-caches
                                   (fn [rt]
                                     (when-not (= [:test-cache] (get-in rt [:job :caches]))
                                       bc/failure))
                                   {:caches [{:id :test-cache
                                              :path "test-cache"}]})
                r @(sut/execute! job (assoc ctx :job job))]
            (is (bc/success? r))
            (is (true? @saved))))))

    (testing "saves artifacts if configured"
      (let [saved (atom false)]
        (with-redefs [art/save-artifacts
                      (fn [rt]
                        (reset! saved true)
                        rt)]
          (let [job (bc/action-job ::job-with-artifacts
                                   (fn [rt]
                                     (when-not (= :test-artifact (-> (get-in rt [:job :save-artifacts])
                                                                     first
                                                                     :id))
                                       bc/failure))
                                   {:save-artifacts [{:id :test-artifact
                                                      :path "test-artifact"}]})
                r @(sut/execute! job (assoc ctx :job job))]
            (is (bc/success? r))
            (is (true? @saved))))))

    (testing "restores artifacts if configured"
      (let [restored (atom false)]
        (with-redefs [art/restore-artifacts
                      (fn [rt]
                        (reset! restored true)
                        rt)]
          (let [job (bc/action-job ::job-with-artifacts
                                   (fn [rt]
                                     (when-not (= :test-artifact (-> (get-in rt [:job :restore-artifacts])
                                                                     first
                                                                     :id))
                                       (assoc bc/failure)))
                                   {:restore-artifacts [{:id :test-artifact
                                                         :path "test-artifact"}]})
                r @(sut/execute! job (assoc ctx :job job))]
            (is (bc/success? r))
            (is (true? @restored))))))

    (testing "recursion"

      (testing "executes actions that return another legacy action"
        (let [result (assoc bc/success :message "recursive result")
              job (bc/action-job "recursing-job"
                                 (constantly {:action (constantly result)}))]
          (is (= result @(sut/execute! job ctx)))))

      (testing "assigns id of parent job to child job"
        (let [job (bc/action-job "parent-job"
                                 (constantly {:action (fn [rt] (assoc bc/success :job-id (get-in rt [:job :id])))}))]
          (is (= "parent-job" (-> @(sut/execute! job ctx)
                                  :job-id))))))

    (testing "returns success when action returns `nil`"
      (is (= bc/success @(sut/execute! (bc/action-job "nil-job" (constantly nil)) ctx)))))

  (let [job (bc/action-job "test-job" (constantly bc/success))
        events (h/fake-events)
        ctx {:events events
             :build {:sid (h/gen-build-sid)
                     :credit-multiplier 10}}]
    (is (bc/success? @(sut/execute! job ctx)))
    
    (testing "fires `job/start` event"
      (let [evt (h/first-event-by-type :job/start (h/received-events events))]
        (is (some? evt))
        (is (= (sut/job-id job) (:job-id evt)))
        (is (spec/valid? ::se/event evt))
        (is (= evt (edn/edn-> (edn/->edn evt))) "Event should be serializable to edn")
        (is (= 10 (:credit-multiplier evt)) "Adds credit multiplier from build")))

    (testing "fires `job/executed` event"
      (let [evt (h/first-event-by-type :job/executed (h/received-events events))]
        (is (some? evt))
        (is (spec/valid? ::se/event evt))
        (is (= (sut/job-id job) (:job-id evt)))
        (is (= evt (edn/edn-> (edn/->edn evt))) "Event should be serializable to edn"))))

  (testing "uses runner credit consumer"
    (let [cr (constantly 666)
          job (bc/action-job "test-job" (constantly bc/success))]
      (is (= 666 (cr/credit-multiplier job {:runner {:credit-consumer cr}}))))))

(deftest execute-jobs!
  (let [ctx {:events (h/fake-events)}]
    (testing "empty if no jobs"
      (is (empty? @(sut/execute-jobs! [] ctx))))

    (testing "executes single start job"
      (let [job (bc/action-job ::start-job
                               (constantly bc/success))]
        (is (= {::start-job {:job job
                             :result bc/success}}
               @(sut/execute-jobs! [job] ctx)))))

    (testing "executes dependent job after dependency"
      (let [p (bc/action-job ::start-job
                             (constantly bc/success))
            c (bc/action-job ::dep-job
                             (fn [rt]
                               (if (= :success (get-in rt [:build :jobs ::start-job :status]))
                                 bc/success
                                 bc/failure))
                             {:dependencies [::start-job]})]
        (is (= {::start-job
                {:job p
                 :result bc/success}
                ::dep-job
                {:job c
                 :result bc/success}}
               @(sut/execute-jobs! [p c] ctx)))))

    (testing "marks any still pending jobs as skipped"
      (let [p (bc/action-job ::start-job
                             (constantly bc/success))
            c (bc/action-job ::dep-job
                             (constantly bc/failure)
                             {:dependencies [::nonexisting-job]})]
        (is (= {::start-job
                {:job p
                 :result bc/success}
                ::dep-job
                {:job c
                 :result bc/skipped}}
               @(sut/execute-jobs! [p c] ctx)))))

    (testing "passes job result to `job/end` event"
      (let [sid ["test-cust" "test-repo"]
            ctx (assoc ctx :build {:sid sid})
            job (bc/action-job ::result-job (constantly (assoc bc/success :message "test message")))]
        (is (= "test message"
               (-> @(sut/execute-jobs! [job] ctx)
                   ::result-job
                   :result
                   :message)))
        (let [evt (->> (:events ctx)
                       (h/received-events)
                       (last))]
          (is (= :job/end (:type evt)) "fires job/end event")
          (is (= "test message"
                 (-> evt
                     :result
                     :message))))))
    
    (testing "adds any additional properties as generated by extensions to result"
      (let [sid ["test-cust" "test-repo"]
            ctx (assoc ctx :build {:sid sid})
            job (bc/action-job ::extension-job (constantly (assoc bc/success ::extension-result "test")))]
        (is (= "test"
               (-> @(sut/execute-jobs! [job] ctx)
                   ::extension-job
                   :result
                   ::extension-result)))
        (let [evt (->> (:events ctx)
                       (h/received-events)
                       (last))]
          (is (= :job/end (:type evt)) "fires job/end event")
          (is (= "test" (-> evt
                            :result
                            ::extension-result))))))))

(deftest execute-next
  (testing "stops when canceled"
    (let [state {"job-1" (bc/action-job "job-1" (constantly bc/success) {:status :success})
                 "job-2" (bc/action-job "job-2" (constantly bc/success) {:status :pending})}
          executing {}
          results {}
          rt {:canceled? (atom true)}
          res (sut/execute-next state executing results rt)]
      (is (map? res))
      (is (= :skipped (get-in res ["job-2" :result :status]))))))

(deftest container-job
  (testing "is a job"
    (is (sut/job? (bc/container-job ::test-job {:container/image "test-img"}))))

  (testing "runs container on execution"
    (let [runner (h/fake-container-runner)]
      (is (bc/success? (-> (bc/container-job ::test-job {})
                           (sut/execute! {:containers runner})
                           (deref))))
      (is (= 1 (count @(:runs runner))))))

  (testing "adds status according to exit code"
    (is (bc/failed? (-> (bc/container-job ::test-job {})
                        (sut/execute! {:containers (h/fake-container-runner {:exit 1})})
                        (deref)))))

  (testing "uses containers credit consumer"
    (let [cr (constantly 555)
          job (bc/container-job ::cr-job {})]
      (is (= 555 (cr/credit-multiplier job {:containers {:credit-consumer cr}})))))

  (testing "adds message to result"
    (let [runner (h/fake-container-runner {:exit 0 :message "test message"})
          job (bc/container-job ::msg-job {})
          r @(sut/execute! job {:containers runner})]
      (is (bc/success? r))
      (is (= "test message" (:message r))))))

(deftest filter-jobs
  (testing "applies filter to jobs"
    (let [[a _ :as jobs] [(dummy-job ::first)
                          (dummy-job ::second)]]
      (is (= [a] (sut/filter-jobs (comp (partial = ::first) sut/job-id) jobs)))))

  (testing "includes dependencies that don't match the filter"
    (let [jobs [(dummy-job ::first {:dependencies [::second]})
                (dummy-job ::second)]]
      (is (= jobs (sut/filter-jobs (comp (partial = ::first) sut/job-id) jobs)))))

  (testing "includes transitive dependencies"
    (let [jobs [(dummy-job ::first {:dependencies [::second]})
                (dummy-job ::second {:dependencies [::third]})
                (dummy-job ::third)]]
      (is (= jobs (sut/filter-jobs (comp (partial = ::first) sut/job-id) jobs))))))

(deftest label-filter
  (testing "matches job by label"
    (let [f (sut/label-filter [[{:label "name"
                                 :value "test-job"}]
                               [{:label "project"
                                 :value "test-project"}]])]
      (is (fn? f))
      (is (true? (f (dummy-job ::first
                               {:labels {"name" "test-job"}}))))
      (is (true? (f (dummy-job ::second
                               {:labels {"project" "test-project"}}))))
      (is (not (f (dummy-job ::third
                             {:labels {"project" "other-project"}})))))))

(deftest resolve-all
  (testing "resolves all jobs"
    (let [[a b] (map dummy-job [::first ::second])]
      (is (= [a b] (sut/resolve-all {} [a (constantly b)])))))

  (testing "removes non-job objects"
    (is (empty? (sut/resolve-all {} [(constantly nil)])))))
