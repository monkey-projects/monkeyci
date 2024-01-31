(ns monkey.ci.listeners-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [events :as e]
             [listeners :as sut]
             [storage :as st]]
            [monkey.ci.helpers :as h]))

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
                 :pipeline {:name "test-pipeline"
                            :index 0}
                 :message "Starting pipeline"}]
        (is (st/sid? (st/save-build-results st sid {:key "value"})))
        (is (some? (sut/pipeline-started ctx evt)))
        (is (= {:key "value"
                :pipelines {0 {:name "test-pipeline"
                               :start-time 100}}}
               (st/find-build-results st sid)))))))

(deftest pipeline-completed
  (testing "patches build results with pipeline info"
    (h/with-memory-store st
      (let [ctx {:storage st}
            sid (random-sid)
            evt {:type :pipeline/end
                 :time 200
                 :sid sid
                 :pipeline {:name "test-pipeline"
                            :index 0}
                 :message "Pipeline completed"
                 :status :success}]
        (is (st/sid? (st/save-build-results st sid {:key "value"
                                                    :pipelines {0
                                                                {:name "test-pipeline"
                                                                 :start-time 100}}})))
        (is (some? (sut/pipeline-completed ctx evt)))
        (is (= {:key "value"
                :pipelines {0
                            {:name "test-pipeline"
                             :start-time 100
                             :end-time 200
                             :status :success}}}
               (st/find-build-results st sid)))))))

(deftest step-started
  (testing "patches build results with step info"
    (h/with-memory-store st
      (let [ctx {:storage st}
            sid (random-sid)
            evt {:type :step/start
                 :time 120
                 :sid sid
                 :index 1
                 :name "test-step"
                 :pipeline {:index 0
                            :name "test-pipeline"}
                 :message "Starting step"}]
        (is (st/sid? (st/save-build-results st sid {:key "value"
                                                    :pipelines {0
                                                                {:name "test-pipeline"
                                                                 :start-time 100}}})))
        (is (some? (sut/step-started ctx evt)))
        (is (= {:key "value"
                :pipelines {0
                            {:name "test-pipeline"
                             :start-time 100
                             :steps {1 {:start-time 120
                                        :name "test-step"}}}}}
               (st/find-build-results st sid)))))))

(deftest step-completed
  (testing "patches build results with step info"
    (h/with-memory-store st
      (let [ctx {:storage st}
            sid (random-sid)
            evt {:type :step/end
                 :time 120
                 :sid sid
                 :index 1
                 :name "test-step"
                 :pipeline {:index 0
                            :name "test-pipeline"}
                 :message "Step completed"
                 :status :success}]
        (is (st/sid? (st/save-build-results st sid {:key "value"
                                                    :pipelines {0
                                                                {:name "test-pipeline"
                                                                 :start-time 100
                                                                 :steps {1 {:start-time 110
                                                                            :name "test-step"}}}}})))
        (is (some? (sut/step-completed ctx evt)))
        (is (= {:key "value"
                :pipelines {0
                            {:name "test-pipeline"
                             :start-time 100
                             :steps {1 {:start-time 110
                                        :end-time 120
                                        :name "test-step"
                                        :status :success}}}}}
               (st/find-build-results st sid)))))))

(deftest build-update-handler
  (testing "creates a fn"
    (is (fn? (sut/build-update-handler {}))))

  (testing "dispatches event by build sid"
    (h/with-bus
      (fn [bus]
        (let [inv (atom {})
              handled (atom 0)]
          (with-redefs [sut/step-started
                        (fn [_ {{:keys [sid]} :build}]
                          (Thread/sleep 100)
                          (swap! inv assoc sid [:started])
                          (swap! handled inc))
                        sut/step-completed
                        (fn [_ {{:keys [sid]} :build}]
                          (Thread/sleep 50)
                          (swap! inv update sid conj :completed)
                          (swap! handled inc))]
            (let [h (sut/build-update-handler {})]
              (->> [:step/start :step/end]
                   (map #(e/register-handler bus % h))
                   (doall))
              (is (some? (e/post-event bus {:type :step/start
                                            :build {:sid ::first}})))
              (is (some? (e/post-event bus {:type :step/start
                                            :build {:sid ::second}})))
              (is (some? (e/post-event bus {:type :step/end
                                            :build {:sid ::first}})))
              (is (some? (e/post-event bus {:type :step/end
                                            :build {:sid ::second}})))
              (is (not= :timeout (h/wait-until #(= 4 @handled) 1000)))
              (doseq [[k r] @inv]
                (is (= [:started :completed] r) (str "for id " k))))))))))
