(ns monkey.ci.containers.common-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.containers.common :as sut]))

(deftest credit-multiplier
  (testing "calculates value according to arch, cpus and memory"
    (is (= 4 (sut/credit-multiplier :arm 2 2)))))

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
                 (se) ; Mark sidecar end for job-1, but not job-2
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
