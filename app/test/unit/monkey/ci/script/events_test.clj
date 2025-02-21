(ns monkey.ci.script.events-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [manifold.deferred :as md]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman.interceptors :as emi]
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

(deftest routes
  (let [types [:script/initializing
               :script/start
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
    (let [jobs [(bc/action-job "test-job" (constantly nil))]
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
  (let [jobs [{:id ::start
               :status :pending}
              {:id ::next
               :status :pending
               :dependencies [::start]}]
        evt {:type :script/start
             :script {:jobs jobs}}]
    (testing "queues all pending jobs without dependencies"
      (is (= [(first jobs)]
             (-> {:event evt}
                 (sut/set-jobs jobs)
                 (sut/script-start)
                 (sut/get-queued))))))

  (testing "returns `script/end` when no jobs"
    (let [r (sut/script-start {:event {:script {:jobs []}}})]
      (is (empty? (sut/get-queued r)))
      (is (= [:script/end] (->> (sut/get-events r)
                                (map :type))))
      (is (bc/failed? (first (sut/get-events r)))))))

(deftest action-job-queued
  (let [jobs {"first" (bc/action-job "first" (constantly nil))
              "second" (bc/container-job "second" {})}]
    (testing "returns action job for execution"
      (is (= [(get jobs "first")] (-> {:event {:job-id "first"}}
                                      (emi/set-state {:jobs jobs})
                                      (sut/action-job-queued)))))
    
    (testing "does nothing if no action job"
      (is (nil? (-> {:event {:job-id "second"}}
                    (emi/set-state {:jobs jobs})
                    (sut/action-job-queued)))))))

(deftest job-end
  (testing "queues pending jobs with completed dependencies")

  (testing "returns `script/end` event when no more jobs to run"
    (is (= [:script/end]
           (->> (sut/job-end {})
                (sut/get-events)
                (map :type))))))
