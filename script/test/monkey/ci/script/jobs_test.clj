(ns monkey.ci.script.jobs-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as ca]
            [clojure.edn :as edn]
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
    
    (testing "restores/saves caches"
        (let [caches (atom {})]
          (letfn [(save-cache [c]
                    (swap! caches update :saved conj c))
                  (restore-cache [c]
                    (swap! caches update :restored conj c))]
            (let [cache {:id :test-cache
                         :path "test-cache"}
                  job (bc/action-job ::job-with-caches
                                     (constantly bc/success)
                                     {:caches [cache]})
                  r (execute-sync job (assoc ctx
                                             :job job
                                             :cache {:save save-cache
                                                     :restore restore-cache}))]
              (is (bc/success? r))
              (is (= [cache] (get @caches :saved)))
              (is (= [cache] (get @caches :restored)))))))

    (testing "saves artifacts"
        (let [saved (atom nil)]
          (let [art {:id :test-artifact
                     :path "test-artifact"}
                job (bc/action-job ::job-with-artifacts
                                   (constantly bc/success)
                                   {:save-artifacts [art]})
                r (execute-sync job (assoc ctx
                                           :job job
                                           :artifact {:save (partial swap! saved conj)
                                                      :restore (constantly nil)}))]
            (is (bc/success? r))
            (is (= [art] @saved)))))

    (testing "restores artifacts"
        (let [restored (atom nil)]
          (let [art {:id :test-artifact
                     :path "test-artifact"}
                job (bc/action-job ::job-with-artifacts
                                   (constantly bc/success)
                                   {:restore-artifacts [art]})
                r (execute-sync job (assoc ctx
                                           :job job
                                           :artifact {:save (constantly nil)
                                                      :restore (partial swap! restored conj)}))]
            (is (bc/success? r))
            (is (= [art] @restored)))))

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
        ch (mmca/get-channel mailman)]
    (is (bc/success? (execute-sync job ctx)))

    (testing "fires `job/start` event"
      (let [evt (h/wait-for-evt mailman (comp (partial = :job/start) :type))]
        (is (some? evt))
        (is (= (sut/job-id job) (:job-id evt)))
        ;;(is (spec/valid? ::es/event evt))
        (is (= evt (edn/read-string (pr-str evt))) "Event should be serializable to edn")
        (is (= 10 (:credit-multiplier evt)) "Adds credit multiplier from build")))

    (testing "fires `job/executed` event"
      (let [evt (h/wait-for-evt mailman (comp (partial = :job/executed) :type))]
        (is (some? evt))
        ;;(is (spec/valid? ::es/event evt))
        (is (= (sut/job-id job) (:job-id evt)))
        (is (= evt (edn/read-string (pr-str evt))) "Event should be serializable to edn")))

    (is (nil? (ca/close! ch)))))
