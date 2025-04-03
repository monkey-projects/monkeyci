(ns monkey.ci.containers.common-test
  (:require [clojure.test :refer [deftest testing is]]
            [io.pedestal.interceptor.chain :as ic]
            [monkey.ci.containers.common :as sut]
            [monkey.ci.events.mailman.interceptors :as emi]))

(deftest credit-multiplier
  (testing "calculates value according to arch, cpus and memory"
    (is (= 4 (sut/credit-multiplier :arm 2 2)))))

(deftest register-job
  (testing "adds job to state"
    (let [{:keys [enter] :as i} sut/register-job
          job {:id ::test-job}
          sid ["test" "build"]]
      (is (keyword? (:name i)))
      
      (is (= job (-> {:event {:job job
                              :job-id ::test-job
                              :sid sid}}
                     (enter)
                     (emi/get-state)
                     (get-in [::sut/jobs ["test" "build"] ::test-job :job])))))))

(deftest ignore-unknown-job
  (let [{:keys [enter] :as i} sut/ignore-unknown-job
        job {:id ::test-job}
        sid ["test" "build"]]
    (is (keyword? (:name i)))

    (testing "returns input context if job is found in state"
      (let [ctx (-> {:event {:job-id ::test-job
                             :sid sid}}
                    (sut/update-job-state assoc :job job))]
        (is (= ctx (enter ctx)))))

    (testing "terminates if job is not found in state"
      (is (nil? (-> {:event {:job-id ::other-job
                             :sid sid}
                     ::ic/queue ::interceptor-queue}
                    (enter)
                    ::ic/queue))))))

(deftest container-end
  (let [se (:enter sut/set-sidecar-status)]
    (testing "fires `job/executed` if sidecar has also ended"
      (is (= :job/executed
             (-> {:event {:job-id "test-job"
                          :result
                          {:status :success}}}
                 (se)
                 (sut/container-end)
                 first
                 :type))))

    (testing "`nil` if sidecar is still running"
      (is (nil? (sut/container-end {:event {:job-id "test-job"}}))))

    (testing "handles multiple jobs"
      (let [[job-1 job-2] (repeatedly 2 random-uuid)]
        (is (nil?
             (-> {:event {:job-id job-1}}
                 (se)      ; Mark sidecar end for job-1, but not job-2
                 (assoc :event {:job-id job-2})
                 (sut/container-end))))))

    (testing "uses container event status"
      (is (= :success
             (-> {:event {:job-id "test-job"
                          :result
                          {:status :success}}}
                 (se)
                 (sut/container-end)
                 first
                 :status))))))

(deftest sidecar-end
  (let [{set-status :enter} sut/set-container-status]
    (testing "fires `job/executed` if container has also ended"
      (is (= :job/executed
             (-> {:event {:job-id "test-job"
                          :result
                          {:status :success}}}
                 (set-status)
                 (sut/sidecar-end)
                 first
                 :type))))

    (testing "`nil` if container is still running"
      (is (nil? (sut/sidecar-end {:event {:job-id "test-job"}}))))

    (testing "handles multiple jobs"
      (let [[job-1 job-2] (repeatedly 2 random-uuid)]
        (is (nil?
             (-> {:event {:job-id job-1
                          :result {:status :success}}}
                 (set-status) ; Mark container end for job-1, but not job-2
                 (assoc :event {:job-id job-2})
                 (sut/container-end))))))

    (testing "uses container status"
      (is (= :success
             (-> {:event {:job-id "test-job"
                          :result {:status :success}}}
                 (set-status)
                 (assoc-in [:event :result :status] :irrelevant)
                 (sut/sidecar-end)
                 first
                 :status))))))
