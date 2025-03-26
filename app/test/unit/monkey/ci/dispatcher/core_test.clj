(ns monkey.ci.dispatcher.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.dispatcher.core :as sut]))

(deftest dispatch
  (testing "executes tasks according to strategy"
    (let [assignments (atom [])
          conf {:get-tasks
                (constantly
                 [::task-1 ::task-2])
                :get-executors
                (constantly
                 [::executor-1 ::executor-2])
                :execute-task
                (fn [task exec]
                  (swap! assignments conj {:task task
                                           :executor exec}))
                :strategy
                (constantly ::executor-1)}]
      (is (= 2 (count (sut/dispatch conf))))
      (is (= [{:task ::task-1
               :executor ::executor-1}
              {:task ::task-2
               :executor ::executor-1}]
             @assignments))))

  (testing "empty when no tasks can be executed"
    (let [conf {:get-tasks
                (constantly
                 [::task-1 ::task-2])
                :get-executors
                (constantly
                 [::executor-1 ::executor-2])
                :execute-task
                (fn [t e]
                  (throw (ex-info "Should not be executed" {:task t :executor e})))
                :strategy
                (constantly nil)}]
      (is (empty? (sut/dispatch conf)))))

  (testing "gets executor state with each assignment"
    (let [exec (atom [::executor-1 ::executor-2])
          conf {:get-tasks
                (constantly
                 [::task-1 ::task-2 ::task-3])
                :get-executors
                (fn [] @exec)
                :execute-task
                (fn [_ e]
                  ;; Remove the executor from the list of available executors
                  (swap! exec (partial remove (partial = e)))
                  e)
                :strategy
                ;; Just pick the first executor
                (fn [_ e] (first e))}]
      (is (= [::executor-1 ::executor-2] (sut/dispatch conf)))
      (is (empty? @exec)))))

(deftest assign-runner
  (testing "assigns to k8s runner when capacity is available"
    (let [task {:cpus 1
                :memory 2}
          runners [{:id :oci
                    :archs [:amd]
                    :count 0}
                   {:id :k8s
                    :archs [:arm :amd]
                    :cpus 4
                    :memory 10}]]
      (is (= (second runners) (sut/assign-runner task runners)))))

  (testing "assigns to oci runner when k8s has no capacity"
    (let [task {:cpus 1
                :memory 2}
          runners [{:id :k8s
                    :archs [:arm :amd]
                    :cpus 1
                    :memory 1}
                   {:id :oci
                    :archs [:amd]
                    :count 10}]]
      (is (= (second runners) (sut/assign-runner task runners)))))

  (testing "`nil` when neither runner has capacity"
    (let [task {:cpus 1
                :memory 2}
          runners [{:id :k8s
                    :archs [:arm :amd]
                    :cpus 1
                    :memory 1}
                   {:id :oci
                    :archs [:amd]
                    :count 0}]]
      (is (nil? (sut/assign-runner task runners)))))

  (testing "assigns to runner that supports platform"
    (let [task {:cpus 1
                :memory 2
                :arch :amd}
          runners [{:id :k8s
                    :archs [:arm]
                    :cpus 1
                    :memory 1}
                   {:id :oci
                    :archs [:amd]
                    :count 1}]]
      (is (= (second runners) (sut/assign-runner task runners)))))

  (testing "prefers the first runner"
    (let [task {:cpus 1
                :memory 2}
          runners [{:id :k8s
                    :archs [:arm :amd]
                    :cpus 4
                    :memory 10}
                   {:id :oci
                    :archs [:amd]
                    :count 10}]]
      (is (= (first runners) (sut/assign-runner task runners))))))

(deftest use-runner-resources
  (testing "decreases available k8s resources"
    (is (= {:id :k8s
            :archs [:amd]
            :memory 2
            :cpus 1}
           (sut/use-runner-resources
            {:id :k8s
             :archs [:amd]
             :memory 4
             :cpus 2}
            {:cpus 1
             :memory 2}))))

  (testing "decreases available oci resources"
    (is (= {:id :oci
            :archs [:amd]
            :count 5}
           (sut/use-runner-resources
            {:id :oci
             :archs [:amd]
             :count 6}
            {:cpus 1
             :memory 2})))))

(deftest release-runner-resources
  (testing "increases available k8s resources"
    (is (= {:id :k8s
            :archs [:amd]
            :memory 6
            :cpus 3}
           (sut/release-runner-resources
            {:id :k8s
             :archs [:amd]
             :memory 4
             :cpus 2}
            {:cpus 1
             :memory 2}))))

  (testing "increases available oci resources"
    (is (= {:id :oci
            :archs [:amd]
            :count 7}
           (sut/release-runner-resources
            {:id :oci
             :archs [:amd]
             :count 6}
            {:cpus 1
             :memory 2})))))

