(ns monkey.ci.dispatcher.runtime-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [monkey.ci.dispatcher.runtime :as sut]
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

    (testing "provides runners"
      (is (some? (:runners sys))))

    (testing "provides storage"
      (is (some? (:storage sys))))))

(deftest http-app
  (testing "`start` creates handler fn"
    (is (fn? (-> (sut/map->HttpApp {})
                 (co/start)
                 :handler)))))

(deftest runners
  (testing "`start` loads initial resources per type"
    (is (= [::start-res]
           (-> {:loaders {:test-runner (constantly ::start-res)}
                :config {:test-runner {}}}
               (sut/map->Runners)
               (co/start)
               :runners)))))

(deftest load-oci
  (let [[cust-id repo-id build-id job-id] (repeatedly (comp str random-uuid))]
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
      (testing "fetches available compute shapes and running containers"
        (is (= {:id :oci
                :archs [:amd]
                :count 4}
               (sut/load-oci {}))))

      (testing "calculates current running builds and jobs from freeform tags"))))
