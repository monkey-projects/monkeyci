(ns monkey.ci.local.console-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.events.mailman.interceptors :as mi]
            [monkey.ci.local.console :as sut]))

(deftest result->state
  (let [{:keys [leave] :as i} sut/result->state]
    (is (keyword? (:name i)))

    (let [r (leave {:result ::test-result})]
      (testing "erases result"
        (is (nil? (:result r))))

      (testing "sets state to result"
        (is (= ::test-result (mi/get-state r)))))))

(deftest build-init
  (testing "sets build in state as result"
    (is (= ::test-build
           (-> {:event
                {:build ::test-build}}
               (mi/set-state {})
               (sut/build-init)
               (sut/get-build))))))

(deftest build-start
  (testing "sets start time in build"
    (is (= ::test-time
           (-> {:event
                {:time ::test-time}}
               (mi/set-state {:build {:build-id "test-build"}})
               (sut/build-start)
               (sut/get-build)
               :start-time)))))

(deftest build-start
  (let [b (-> {:event
                {:time ::test-time
                 :status :success}}
               (mi/set-state {:build {:build-id "test-build"}})
               (sut/build-end)
               (sut/get-build))]
    (testing "sets end time in build"
      (is (= ::test-time (:end-time b))))

    (testing "sets status"
      (is (= :success (:status b))))))

(deftest script-start
  (testing "adds sorted jobs to state"
    (is (= ["job-1" "job-2"]
           (->> {:event
                 {:jobs [{:id "job-2"
                          :dependencies ["job-1"]}
                         {:id "job-1"}]}}
                (sut/script-start)
                (sut/get-jobs)
                (map :id))))))

(deftest script-end
  (testing "sets script msg in build"
    (is (= "test msg"
           (-> {:event
                {:message "test msg"}}
               (sut/script-end)
               (sut/get-build)
               :script-msg)))))

(deftest routes
  (testing "handles build and job events"
    (let [exp [:build/initializing
               :build/start
               :build/end
               :script/start
               :script/end
               :job/initializing
               :job/start
               :job/end]
          r (->> (sut/make-routes {:state (atom {})})
                 (map first)
                 (set))]
      (doseq [e exp]
        (is (r e) (str "should handle " e))))))
