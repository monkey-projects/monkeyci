(ns monkey.ci.dispatcher.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.dispatcher.core :as sut]))

(deftest assign-runner
  (testing "assigns to k8s runner when capacity is available"
    (let [task {:resources {:cpus 1
                            :memory 2}}
          runners [{:id :oci
                    :archs [:amd]
                    :count 0}
                   {:id :k8s
                    :archs [:arm :amd]
                    :cpus 4
                    :memory 10}]]
      (is (= (second runners) (sut/assign-runner task runners)))))

  (testing "assigns to oci runner when k8s has no capacity"
    (let [task {:resources {:cpus 1
                            :memory 2}}
          runners [{:id :k8s
                    :archs [:arm :amd]
                    :cpus 1
                    :memory 1}
                   {:id :oci
                    :archs [:amd]
                    :count 10}]]
      (is (= (second runners) (sut/assign-runner task runners)))))

  (testing "`nil` when neither runner has capacity"
    (let [task {:resources
                {:cpus 1
                 :memory 2}}
          runners [{:id :k8s
                    :archs [:arm :amd]
                    :cpus 1
                    :memory 1}
                   {:id :oci
                    :archs [:amd]
                    :count 0}]]
      (is (nil? (sut/assign-runner task runners)))))

  (testing "assigns to runner that supports platform"
    (let [task {:resources {:cpus 1
                            :memory 2}
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
    (let [task {:resources
                {:cpus 1
                 :memory 2}}
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

(deftest get-next-queued-task
  (testing "nothing if no tasks"
    (is (nil? (sut/get-next-queued-task [] {} nil))))

  (testing "picks oldest that can be executed by the runner"
    (let [[a b :as qt] [{:id 1
                         :creation-time 200
                         :task {:type :build
                                :resources {:memory 1
                                            :cpus 1}}}
                        {:id 2
                         :creation-time 100
                         :task {:type :build
                                :resources {:memory 1
                                            :cpus 1}}}]
          runners [{:id :oci
                    :count 10
                    :archs [:amd]}]]
      (is (= b (sut/get-next-queued-task qt runners :oci)))))

  (testing "removes tasks that cannot be executed by the runner due to arch"
    (let [[_ b :as qt] [{:id 1
                         :creation-time 100
                         :task {:type :build
                                :arch :arm
                                :resources {:memory 1
                                            :cpus 1}}}
                        {:id 2
                         :creation-time 200
                         :task {:type :build
                                :arch :amd
                                :resources {:memory 1
                                            :cpus 1}}}]
          runners [{:id :oci
                    :count 10
                    :archs [:amd]}]]
      (is (= b (sut/get-next-queued-task qt runners :oci)))))

  (testing "removes tasks that cannot be executed by the runner due to resources"
    (let [[_ b :as qt] [{:id 1
                         :creation-time 100
                         :task {:type :build
                                :resources {:memory 4
                                            :cpus 1}}}
                        {:id 2
                         :creation-time 200
                         :task {:type :build
                                :resources {:memory 1
                                            :cpus 1}}}]
          runners [{:id :k8s
                    :memory 2
                    :cpus 1
                    :archs [:amd]}]]
      (is (= b (sut/get-next-queued-task qt runners :k8s)))))

  (testing "`nil` if no tasks could be scheduled due to resource requirements"
    (let [qt [{:id 1
               :creation-time 200
               :task {:type :build
                      :resources {:memory 1
                                  :cpus 1}}}
              {:id 2
               :creation-time 100
               :task {:type :build
                      :resources {:memory 1
                                  :cpus 1}}}]
          runners [{:id :k8s
                    :memory 0
                    :cpus 1
                    :archs [:amd :arm]}]]
      (is (nil? (sut/get-next-queued-task qt runners :k8s)))))

  (testing "does not schedule smaller tasks before larger one if the latter can be only run by that runner"
    (let [qt [{:id ::small
               :creation-time 200
               :task {:type :build
                      :resources {:memory 1
                                  :cpus 1}}}
              {:id ::large
               :creation-time 100
               :task {:type :build
                      :arch :arm
                      :resources {:memory 10
                                  :cpus 4}}}]
          runners [{:id :k8s
                    :memory 8
                    :cpus 2
                    :archs [:amd :arm]}
                   {:id :oci
                    :count 5
                    :archs [:amd]}]]
      (is (nil? (sut/get-next-queued-task qt runners :k8s))))))

(deftest arch-filter
  (testing "keeps tasks without archs"
    (let [tasks [{:task {}}]]
      (is (= tasks ((sut/arch-filter {:archs [:arm :amd]}) tasks)))))

  (testing "keeps tasks with matching archs"
    (let [[a :as tasks] [{:task {:arch :arm}}
                         {:task {:arch :amd}}]]
      (is (= [a] ((sut/arch-filter {:archs [:arm]}) tasks))))))
