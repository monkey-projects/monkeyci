(ns monkey.ci.dispatcher.events-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.dispatcher.events :as sut]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.storage :as st]
            [monkey.ci.test.helpers :as h]
            [monkey.mailman.core :as mmc]))

(deftest make-routes
  (h/with-memory-store st
    (let [evts [:container/job-queued :build/queued :job/end :build/end]
          r (sut/make-routes (atom {}) st)]
      (doseq [e evts]
        (testing (format "handles `%s`" e)
          (is (contains? (set (map first r)) e)))))

    (let [state (atom {:runners [{:id :k8s
                                  :archs [:arm]
                                  :memory 4
                                  :cpus 1}
                                 {:id :oci
                                  :archs [:amd]
                                  :count 10}]})
          router (-> state
                     (sut/make-routes st)
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
                      (map :type)))))

        (testing "when no capacity"
          (let [build (h/gen-build)]
            (is (some? (reset! state {:runners [{:id :oci
                                                 :archs [:amd]
                                                 :count 0}]})))
            (is (nil? (-> {:type :build/queued
                           :build build}
                          (router)
                          first
                          :result))
                "does not dispatch event")

            (testing "adds to queued list"
              (is (= 1 (count (::sut/queued-list @state)))))

            (testing "saves to database"
              (let [l (st/list-queued-tasks st)]
                (is (= 1 (count l)))
                (is (= build (get-in (first l) [:details :build])))))))))))

(deftest build-queued
  (testing "dispatches to available runner according to assignment"
    (let [r (-> {:event
                 {:type :build-queued
                  :build {}}}
                (sut/set-assignment {:runner :k8s})
                (sut/build-queued))]
      (is (= [:k8s/build-scheduled]
             (->> r (map :type))))))

  (testing "when no assignment, returns `nil`"
    (is (nil? (sut/build-queued {})))))

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

(deftest add-runner-assignment
  (let [{:keys [enter] :as i} sut/add-runner-assignment]
    (is (keyword? (:name i)))

    (testing "`enter`"
      (testing "assigns runner according to task requirements"
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
                   (sut/get-assignment)))))

      (testing "queues task when no runner available"
        (let [task {:build {}
                    :memory 2
                    :cpus 1}
              ctx (-> {}
                      (sut/set-task task)
                      (enter))]
          (is (nil? (sut/get-assignment ctx)))
          (is (= task (sut/get-queued ctx)))))

      (testing "queues task when runner available, but other tasks are queued"
        (let [task {:build {}
                    :memory 2
                    :cpus 1}
              other-task {:memory 2
                          :cpus 2}
              ctx (-> {}
                      (sut/set-task task)
                      (emi/set-state {::sut/queued-list [other-task]})
                      (sut/set-runners [{:id :oci
                                         :count 10}])
                      (enter))]
          (is (nil? (sut/get-assignment ctx)))
          (is (= task (sut/get-queued ctx))))))))

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

    (testing "`leave`"
      (testing "saves assignment to state"
        (let [a ::test-assignment]
          (is (= a (-> {:event {:id ::test-id}}
                       (sut/set-assignment a)
                       (leave)
                       (sut/get-state-assignment ::test-id))))))

      (testing "does not save when no assignment"
          (is (empty? (-> {:event {:id ::test-id}}
                          (leave)
                          (emi/get-state)
                          :assignments)))))))

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
