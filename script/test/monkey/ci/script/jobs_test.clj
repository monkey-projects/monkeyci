(ns monkey.ci.script.jobs-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as ca]
            [monkey.ci.build.core :as bc]
            [monkey.ci.script
             [helpers :as h]
             [jobs :as sut]]
            [monkey.mailman.core-async :as mmca]))

(defn dummy-job
  ([id & [opts]]
   (bc/action-job id (constantly nil) opts))
  ([]
   (dummy-job ::test-job)))

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

(defn- execute-sync [job ctx]
  (first (ca/alts!! [(sut/execute! job ctx)
                     (ca/timeout 100)])))

(deftest action-job
  (let [job (bc/action-job ::test-job (constantly bc/success))
        ctx {:mailman (mmca/core-async-broker)
             :containers ::test-containers}]
    (testing "is a job"
      (is (sut/job? job)))
    
    (testing "executes action"
      (is (bc/success? (execute-sync job ctx))))

    (testing "only receives job, build and api in context"
      (is (bc/success? (execute-sync (bc/action-job ::test-job #(when (:containers %) bc/failure))
                                     ctx))))

    (testing "rewrites work dir to absolute path against checkout dir"
      (let [job (bc/action-job ::wd-job
                               (fn [ctx]
                                 (assoc bc/success :wd (bc/work-dir ctx)))
                               {:work-dir "sub/dir"})]
        (is (= "/checkout/sub/dir"
               (-> (execute-sync job (assoc ctx
                                            :build {:checkout-dir "/checkout"}
                                            :job job))
                   :wd)))))
    
    #_(testing "restores/saves cache if configured"
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

    #_(testing "saves artifacts if configured"
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

    #_(testing "restores artifacts if configured"
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
          (is (= result (execute-sync job ctx)))))

      (testing "assigns id of parent job to child job"
        (let [job (bc/action-job "parent-job"
                                 (constantly
                                  {:type :action
                                   :action (fn [rt] (assoc bc/success :job-id (get-in rt [:job :id])))}))]
          (is (= "parent-job" (-> (execute-sync job ctx)
                                  :job-id))))))

    (testing "returns success when action returns `nil`"
      (is (= bc/success (execute-sync (bc/action-job "nil-job" (constantly nil)) ctx))))

    (testing "drops invalid result, adds warning"
      (let [r (execute-sync (bc/action-job "invalid-job" (constantly "invalid result")) ctx)]
        (is (bc/success? r))
        (is (= 1 (count (bc/warnings r))))))

    (testing "captures output to stdout"
      (let [msg "test output"
            job (bc/action-job "outputting-job" (fn [_]
                                                  (println msg)))
            res (execute-sync job ctx)]
        (is (bc/success? res))
        (is (= msg (some-> (:output res) (.strip)))))))

  (let [job (bc/action-job "test-job" (constantly bc/success))
        mailman (mmca/core-async-broker)
        ctx {:mailman mailman
             :build {:sid ["test" "build" "sid"]
                     :credit-multiplier 10}}
        recv (h/store-events mailman)
        ch (mmca/get-channel mailman)]
    (is (bc/success? (execute-sync job ctx)))
    (is (nil? (ca/close! ch)))

    (let [events (group-by :type @recv)]
      (testing "fires `job/start` event"
        (let [evt (first (get events :job/start))]
          (is (some? evt))
          (is (= (sut/job-id job) (:job-id evt)))
          ;;(is (spec/valid? ::es/event evt))
          ;;(is (= evt (edn/edn-> (edn/->edn evt))) "Event should be serializable to edn")
          (is (= 10 (:credit-multiplier evt)) "Adds credit multiplier from build")))

      (testing "fires `job/executed` event"
        (let [evt (first (get events :job/executed))]
          (is (some? evt))
          ;;(is (spec/valid? ::es/event evt))
          (is (= (sut/job-id job) (:job-id evt)))
          ;;(is (= evt (edn/edn-> (edn/->edn evt))) "Event should be serializable to edn")
          )))))
