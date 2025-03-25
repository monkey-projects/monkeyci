(ns monkey.ci.containers.dispatcher-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.containers.dispatcher :as sut]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.mailman.core :as mmc]))

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

(deftest make-routes
  (let [r (sut/make-routes {})]
    (doseq [e [:container/job-queued :build/queued :job/end :build/end]]
      (testing (format "handles `%s`" e)
        (is (contains? (set (map first r)) e)))))

  (let [router (-> {:runners [{:id :k8s
                               :archs [:arm]
                               :memory 4
                               :cpus 1}
                              {:id :oci
                               :archs [:amd]
                               :count 10}]}
                   (sut/make-routes)
                   (mmc/router))]
    (testing "`:build/queued`"
      (testing "returns `k8s/build-scheduled` when capacity"
        (is (= [:k8s/build-scheduled]
               (->> {:type :build/queued
                     :build {}}
                    (router)
                    first
                    :result
                    (map :type)))))

      (testing "returns `oci/build-scheduled` when no k8s capacity"
        (is (= [:oci/build-scheduled]
               (->> {:type :build/queued
                     :build {}}
                    (router)
                    first
                    :result
                    (map :type))))))))

(deftest build-queued
  (testing "dispatches to available runner according to assignment"
    (let [r (-> {:event
                 {:type :build-queued
                  :build {}}}
                (sut/set-assignment {:runner :k8s})
                (sut/build-queued))]
      (is (= [:k8s/build-scheduled]
             (->> r (map :type))))))

  (testing "when no assignment, fails build"))

(deftest add-build-task
  (let [{:keys [enter] :as i} sut/add-build-task]
    (is (keyword? (:name i)))

    (testing "`enter` adds task according to build"
      (let [t (-> {:event
                   {:build {}}}
                  (enter)
                  (sut/get-task))]
        (is (some? t))
        (is (number? (:cpus t)))
        (is (number? (:memory t)))))))

(deftest assign-runner
  (let [{:keys [enter] :as i} sut/add-runner-assignment]
    (is (keyword? (:name i)))

    (testing "`enter` assigns runner according to task requirements"
      (is (= {:runner :oci
              :resources {:memory 2
                          :cpus 1}}
             (-> {}
                 (sut/set-runners [{:id :k8s
                                    :archs [:arm]
                                    :memory 6
                                    :cpus 2}
                                   {:id :oci
                                    :count 10
                                    :archs [:amd]}])
                 (sut/set-task {:type :build
                                :arch :amd
                                :memory 2
                                :cpus 1})
                 (enter)
                 (sut/get-assignment)))))))

(deftest consume-resources
  (let [{:keys [leave] :as i} sut/consume-resources]
    (is (keyword? (:name i)))

    (testing "`leave` updates runner resources in state"
      (let [r (-> {}
                  (sut/set-assignment {:runner :k8s
                                       :resources {:memory 2
                                                   :cpus 1}})
                  (sut/set-runners [{:id :k8s
                                     :memory 6
                                     :cpus 2}])
                  (leave)
                  (sut/get-runners))]
        (is (= [{:id :k8s
                 :memory 4
                 :cpus 1}]
               r))))))

(deftest release-resources
  (let [{:keys [enter] :as i} sut/release-resources]
    (is (keyword? (:name i)))
    
    (testing "`enter` updates runner state according to consumed resources by task"
      (let [r (-> {}
                  (sut/set-assignment {:runner :k8s
                                       :resources {:memory 2
                                                   :cpus 1}})
                  (sut/set-runners [{:id :k8s
                                     :memory 3
                                     :cpus 2}])
                  (enter)
                  (sut/get-runners))]
        (is (= [{:id :k8s
                 :memory 5
                 :cpus 3}]
               r))))))

(deftest save-assignment
  (let [{:keys [leave] :as i} (sut/save-assignment :id)]
    (is (keyword? (:name i)))

    (testing "`leave` saves assignment to state"
      (let [a ::test-assignment]
        (is (= a (-> {:event {:id ::test-id}}
                     (sut/set-assignment a)
                     (leave)
                     (sut/get-state-assignment ::test-id))))))))

(deftest load-assignment
  (let [{:keys [enter] :as i} (sut/load-assignment :id)]
    (is (keyword? (:name i)))

    (testing "`enter` gets assignment from state"
      (let [a ::test-assignment]
        (is (= a (-> {:event {:id ::test-id}}
                     (sut/set-state-assignment ::test-id a)
                     (enter)
                     (sut/get-assignment))))))))

(deftest clear-assignment
  (let [{:keys [leave] :as i} (sut/clear-assignment :id)]
    (is (keyword? (:name i)))

    (testing "`leave` removes assignment from state"
      (is (nil? (-> {:event {:id ::test-id}}
                    (sut/set-state-assignment ::test-id ::test-assignment)
                    (leave)
                    (sut/get-state-assignment ::test-id)))))))
