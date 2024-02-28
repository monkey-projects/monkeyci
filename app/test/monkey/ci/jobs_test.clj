(ns monkey.ci.jobs-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.build.core :as bc]
            [monkey.ci
             [artifacts :as art]
             [cache :as cache]
             [containers :as co]
             [jobs :as sut]]))

(defn dummy-job [id & [opts]]
  (bc/action-job id (constantly nil) opts))

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

(deftest resolve-job
  (testing "returns job"
    (let [job (dummy-job ::test-job)]
      (is (= job (sut/resolve-job job {})))))

  (testing "invokes fn to return job"
    (let [job (dummy-job ::indirect-job)]
      (is (= job (sut/resolve-job (constantly job) {})))))

  (testing "recurses into job"
    (let [job (dummy-job ::recursive-job)]
      (is (= job (sut/resolve-job (constantly (constantly job)) {})))))

  (testing "returns job fn as action job"
    (let [job (with-meta (constantly ::ok) {:job true :job/id "test-job"})
          r (sut/resolve-job job {})]
      (is (bc/action-job? r))
      (is (= job (:action r)))
      (is (= "test-job" (bc/job-id job)))))

  (testing "resolves var"
    (is (= test-job (sut/resolve-job #'test-job {}))))

  (testing "resolves `nil`"
    (is (nil? (sut/resolve-job nil {})))))

(deftest action-job
  (let [job (bc/action-job ::test-job (constantly ::result))]
    (testing "is a job"
      (is (sut/job? job)))
    
    (testing "executes action"
      (is (= ::result @(sut/execute! job {}))))

    (testing "rewrites work dir to absolute path against checkout dir"
      (let [job (bc/action-job ::wd-job
                               (fn [rt]
                                 (assoc bc/success :wd (bc/work-dir rt)))
                               {:work-dir "sub/dir"})]
        (is (= "/checkout/sub/dir"
               (-> (sut/execute! job {:build {:checkout-dir "/checkout"}
                                      :job job})
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
                rt {:job job}
                r (sut/execute! job rt)]
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
                                       (assoc bc/failure)))
                                   {:save-artifacts [{:id :test-artifact
                                                      :path "test-artifact"}]})
                rt {:job job}
                r (sut/execute! job rt)]
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
                rt {:job job}
                r (sut/execute! job rt)]
            (is (bc/success? r))
            (is (true? @restored))))))))

(deftest execute-jobs!
  (testing "empty if no jobs"
    (is (empty? @(sut/execute-jobs! [] {}))))

  (testing "executes single start job"
    (let [job (bc/action-job ::start-job
                              (constantly bc/success))]
      (is (= {::start-job {:job job
                           :result bc/success}}
             @(sut/execute-jobs! [job] {})))))

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
             @(sut/execute-jobs! [p c] {}))))))

(deftest container-job
  (testing "is a job"
    (is (sut/job? (bc/container-job ::test-job {:container/image "test-img"}))))

  (testing "runs container on execution"
    (with-redefs [co/run-container (constantly ::ok)]
      (is (= ::ok (-> (bc/container-job ::test-job {})
                      (sut/execute! {})))))))

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
