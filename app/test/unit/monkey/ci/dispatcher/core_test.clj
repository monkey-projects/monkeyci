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

