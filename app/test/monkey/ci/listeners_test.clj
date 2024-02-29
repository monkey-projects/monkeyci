(ns monkey.ci.listeners-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [listeners :as sut]
             [storage :as st]]
            [monkey.ci.helpers :as h]))

(defn- random-sid []
  (repeatedly 4 (comp str random-uuid)))

(deftest save-build-result
  (testing "writes to build result object"
    (h/with-memory-store st
      (let [ctx {:storage st}
            sid ["test-customer" "test-repo" "test-build"]
            evt {:type :build/completed
                 :build {:sid sid}
                 :exit 0
                 :result :success}]
        (is (st/sid? (sut/save-build-result ctx evt)))
        (is (= {:exit 0
                :result :success}
               (st/find-build-results st sid)))))))

(deftest job-started
  (testing "patches build results with job info"
    (h/with-memory-store st
      (let [ctx {:storage st}
            sid (random-sid)
            evt {:type :job/start
                 :time 120
                 :sid sid
                 :id "test-job"
                 :message "Starting job"}]
        (is (st/sid? (st/save-build-results st sid {:key "value"})))
        (is (some? (sut/job-started ctx evt)))
        (is (= {:key "value"
                :jobs {"test-job"
                       {:start-time 120
                        :id "test-job"}}}
               (st/find-build-results st sid)))))))

(deftest job-completed
  (testing "patches build results with job info"
    (h/with-memory-store st
      (let [ctx {:storage st}
            sid (random-sid)
            evt {:type :job/end
                 :time 120
                 :sid sid
                 :id "test-job"
                 :message "Job completed"
                 :status :success}]
        (is (st/sid? (st/save-build-results st sid {:key "value"
                                                    :jobs {"test-job"
                                                           {:start-time 110
                                                            :id "test-job"}}})))
        (is (some? (sut/job-completed ctx evt)))
        (is (= {:key "value"
                :jobs {"test-job" {:start-time 110
                                   :end-time 120
                                   :id "test-job"
                                   :status :success}}}
               (st/find-build-results st sid)))))))

(deftest build-update-handler
  (testing "creates a fn"
    (is (fn? (sut/build-update-handler {}))))

  (testing "dispatches event by build sid"
    (let [inv (atom {})
          handled (atom 0)]
      (with-redefs [sut/job-started
                    (fn [_ {{:keys [sid]} :build}]
                      (Thread/sleep 100)
                      (swap! inv assoc sid [:started])
                      (swap! handled inc))
                    sut/job-completed
                    (fn [_ {{:keys [sid]} :build}]
                      (Thread/sleep 50)
                      (swap! inv update sid conj :completed)
                      (swap! handled inc))]
        (let [h (sut/build-update-handler {:events {:poster (fn [_]
                                                              (swap! handled inc))}})]
          (h {:type :job/start
              :build {:sid ::first}})
          (h {:type :job/start
              :build {:sid ::second}})
          (h {:type :job/end
              :build {:sid ::first}})
          (h {:type :job/end
              :build {:sid ::second}})
          (is (not= :timeout (h/wait-until #(= 4 @handled) 1000)))
          (doseq [[k r] @inv]
            (is (= [:started :completed] r) (str "for id " k))))))))
