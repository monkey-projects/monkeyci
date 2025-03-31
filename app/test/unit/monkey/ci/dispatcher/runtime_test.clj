(ns monkey.ci.dispatcher.runtime-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [monkey.ci.dispatcher
             [runtime :as sut]
             [state :as ds]]
            [monkey.ci.oci :as oci]
            [monkey.oci.container-instance.core :as ci]))

(deftest make-system
  (let [sys (sut/make-system {:mailman {:type :manifold}
                              :storage {:type :memory}})]
    (testing "provides http server"
      (is (some? (:http-server sys))))

    (testing "provides http app"
      (is (some? (:http-app sys))))

    (testing "provides metrics"
      (is (some? (:metrics sys))))

    (testing "provides mailman"
      (is (some? (:mailman sys))))

    (testing "provides event routes"
      (is (some? (:event-routes sys))))

    (testing "provides event poller")

    (testing "provides initial state"
      (is (some? (:init-state sys))))

    (testing "provides storage"
      (is (some? (:storage sys))))))

(deftest http-app
  (testing "`start` creates handler fn"
    (is (fn? (-> (sut/map->HttpApp {})
                 (co/start)
                 :handler)))))

(deftest initial-state
  (testing "`start` loads initial resources per type"
    (is (= [::start-res]
           (-> {:loaders {:test-runner (constantly {:runners [::start-res]})}
                :config {:test-runner {}}}
               (sut/map->InitialState)
               (co/start)
               :state
               :runners)))))

(deftest load-oci
  (let [[cust-id repo-id build-id job-id :as sid] (repeatedly 4 (comp str random-uuid))]
    (with-redefs [oci/list-instance-shapes
                  (constantly (md/success-deferred
                               [{:arch :amd
                                 :shape "Test-Amd-Shape"}]))
                  ci/list-container-instances
                  (constantly (md/success-deferred
                               [{:id "build-1"
                                 :freeform-tags {"customer-id" cust-id
                                                 "repo-id" repo-id
                                                 "build-id" build-id}
                                 :lifecycle-state "ACTIVE"}
                                {:id "build-job-1"
                                 :freeform-tags {"customer-id" cust-id
                                                 "repo-id" repo-id
                                                 "build-id" build-id
                                                 "job-id" job-id}
                                 :lifecycle-state "CREATING"}]))]
      (let [s (sut/load-oci {})]
        (testing "fetches available compute shapes and running containers"
          (is (= [{:id :oci
                   :archs [:amd]
                   :count 4}]
                 (:runners s))))

        (testing "calculates current running builds and jobs from freeform tags"
          (let [build-sid (take 3 sid)]
            (is (= :oci (-> s (ds/get-assignment build-sid) :runner)))
            (is (= :oci (-> s (ds/get-assignment [build-sid (last sid)]) :runner)))))))))

(deftest ci->task
  (testing "sets resources from shape config"
    (is (= {:memory 2
            :cpus 1}
           (-> {:shape-config {:ocpus 1
                               :memory-in-g-bs 2}}
               (sut/ci->task)
               (select-keys [:memory :cpus])))))

  (testing "sets arch from shape"
    (is (= :amd (:arch (sut/ci->task
                        {:shape "CI.Standard.E4.Flex"}))))))

(deftest ci->task-id
  (testing "extracts build sid from freeform tags"
    (is (= ["test-cust" "test-repo" "test-build"]
           (sut/ci->task-id {:freeform-tags
                             {"customer-id" "test-cust"
                              "repo-id" "test-repo"
                              "build-id" "test-build"}}))))

  (testing "extracts job id"
    (is (= [["test-cust" "test-repo" "test-build"] "test-job"]
           (sut/ci->task-id {:freeform-tags
                             {"customer-id" "test-cust"
                              "repo-id" "test-repo"
                              "build-id" "test-build"
                              "job-id" "test-job"}})))))
