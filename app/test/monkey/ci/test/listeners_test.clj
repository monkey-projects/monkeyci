(ns monkey.ci.test.listeners-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [listeners :as sut]
             [storage :as st]]
            [monkey.ci.test.helpers :as h]))

(defn- random-sid []
  (repeatedly 4 (comp str random-uuid)))

(deftest save-build-result
  (testing "writes to build result object"
    (h/with-memory-store st
      (let [ctx {:storage st}
            sid ["test-customer" "test-project" "test-repo" "test-build"]
            evt {:type :build/completed
                 :build {:sid sid}
                 :exit 0
                 :result :success}]
        (is (st/sid? (sut/save-build-result ctx evt)))
        (is (= {:exit 0
                :result :success}
               (st/find-build-results st sid)))))))

(deftest pipeline-started
  (testing "patches build results with pipeline info"
    (h/with-memory-store st
      (let [ctx {:storage st}
            sid (random-sid)
            evt {:type :pipeline/start
                 :time 100
                 :sid sid
                 :pipeline "test-pipeline"
                 :message "Starting pipeline"}
            _ (st/save-build-results st sid {:key "value"})]
        (is (some? (sut/pipeline-started ctx evt)))
        (is (= {:key "value"
                :pipelines {"test-pipeline" {:start-time 100}}}
               (st/find-build-results st sid)))))))

(deftest pipeline-completed
  (testing "patches build results with pipeline info"
    (h/with-memory-store st
      (let [ctx {:storage st}
            sid (random-sid)
            evt {:type :pipeline/end
                 :time 200
                 :sid sid
                 :pipeline "test-pipeline"
                 :message "Pipeline completed"
                 :status :success}
            _ (st/save-build-results st sid {:key "value"
                                             :pipelines {"test-pipeline"
                                                         {:start-time 100}}})]
        (is (some? (sut/pipeline-completed ctx evt)))
        (is (= {:key "value"
                :pipelines {"test-pipeline" {:start-time 100
                                             :end-time 200
                                             :status :success}}}
               (st/find-build-results st sid)))))))
