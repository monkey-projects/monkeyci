(ns monkey.ci.jobs-test
  (:require [clojure.spec.alpha :as spec]
            [clojure.test :refer [deftest is testing]]
            [monkey.ci
             [artifacts :as art]
             [cache :as cache]
             [edn :as edn]
             [jobs :as sut]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.spec.events :as se]
            [monkey.ci.test
             [helpers :as h]
             [mailman :as tm]]))

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

  (testing "resolves action job as itself"
    (let [job (bc/action-job "test-job" (constantly nil))]
      (is (= [job] (sut/resolve-jobs job {})))))

  (testing "resolves container job as itself"
    (let [job (bc/container-job "test-job" {:image "test-img"})]
      (is (= [job] (sut/resolve-jobs job {})))))

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
      (is (every? bc/action-job? r)))))

(deftest action-job
  (let [job (bc/action-job ::test-job (constantly bc/success))
        ctx {:mailman (tm/test-component)
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

      (testing "executes actions that return another action"
        (let [result (assoc bc/success :message "recursive result")
              job (bc/action-job "recursing-job"
                                 (constantly {:type :action
                                              :action (constantly result)}))]
          (is (= result @(sut/execute! job ctx)))))

      (testing "assigns id of parent job to child job"
        (let [job (bc/action-job "parent-job"
                                 (constantly
                                  {:type :action
                                   :action (fn [rt] (assoc bc/success :job-id (get-in rt [:job :id])))}))]
          (is (= "parent-job" (-> @(sut/execute! job ctx)
                                  :job-id))))))

    (testing "returns success when action returns `nil`"
      (is (= bc/success @(sut/execute! (bc/action-job "nil-job" (constantly nil)) ctx))))

    (testing "drops invalid result, adds warning"
      (let [r @(sut/execute! (bc/action-job "invalid-job" (constantly "invalid result")) ctx)]
        (is (bc/success? r))
        (is (= 1 (count (bc/warnings r))))))

    (testing "captures output to stdout"
      (let [msg "test output"
            job (bc/action-job "outputting-job" (fn [_]
                                                  (println msg)))
            res @(sut/execute! job ctx)]
        (is (bc/success? res))
        (is (= msg (some-> (:output res) (.strip)))))))

  (let [job (bc/action-job "test-job" (constantly bc/success))
        mailman (tm/test-component)
        ctx {:mailman mailman
             :build {:sid (h/gen-build-sid)
                     :credit-multiplier 10}}]
    (is (bc/success? @(sut/execute! job ctx)))
    
    (testing "fires `job/start` event"
      (let [evt (h/first-event-by-type :job/start (tm/get-posted mailman))]
        (is (some? evt))
        (is (= (sut/job-id job) (:job-id evt)))
        (is (spec/valid? ::se/event evt))
        (is (= evt (edn/edn-> (edn/->edn evt))) "Event should be serializable to edn")
        (is (= 10 (:credit-multiplier evt)) "Adds credit multiplier from build")))

    (testing "fires `job/executed` event"
      (let [evt (h/first-event-by-type :job/executed (tm/get-posted mailman))]
        (is (some? evt))
        (is (spec/valid? ::se/event evt))
        (is (= (sut/job-id job) (:job-id evt)))
        (is (= evt (edn/edn-> (edn/->edn evt))) "Event should be serializable to edn")))))

(deftest container-job
  (testing "is a job"
    (is (sut/job? (bc/container-job ::test-job {:container/image "test-img"})))))

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
                               {:labels {"name" ["test-job"]}}))))
      (is (true? (f (dummy-job ::second
                               {:labels {"project" ["test-project"]}}))))
      (is (not (f (dummy-job ::third
                             {:labels {"project" ["other-project"]}})))))))

(deftest resolve-all
  (testing "resolves all jobs"
    (let [[a b] (map dummy-job [::first ::second])]
      (is (= [a b] (sut/resolve-all {} [a (constantly b)])))))

  (testing "removes non-job objects"
    (is (empty? (sut/resolve-all {} [(constantly nil)])))))
