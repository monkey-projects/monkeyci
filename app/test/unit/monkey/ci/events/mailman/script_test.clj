(ns monkey.ci.events.mailman.script-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events.mailman.script :as sut]
            [monkey.ci.spec.events :as se]
            [monkey.ci.helpers :as h]))

(deftest with-state
  (let [state (atom {:key :initial})
        {:keys [enter leave] :as i} (sut/with-state state)]
    (is (keyword? (:name i)))

    (testing "`enter` adds state to context"
      (is (= @state (-> (enter {})
                        (sut/get-state)))))

    (testing "`leave` updates state"
      (is (some? (-> (-> {}
                         (sut/set-state {:key :updated})
                         (leave)))))
      (is (= {:key :updated}
             @state)))))

(deftest load-jobs
  (let [{:keys [enter] :as i} sut/load-jobs]
    (is (keyword? (:name i)))
    
    (testing "loads build jobs from script dir"
      (is (not-empty (-> {}
                         (sut/set-build {:script
                                         {:script-dir "examples/basic-clj"}})
                         (enter)
                         (sut/get-jobs)))))))

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

(deftest job-end
  (testing "queues pending jobs with completed dependencies")

  (testing "returns `script/end` event when no more jobs to run"
    (is (= [:script/end]
           (->> (sut/job-end {})
                (sut/get-events)
                (map :type))))))
