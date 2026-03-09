(ns monkey.ci.web.api.job-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [cuid :as cuid]
             [storage :as st]]
            [monkey.ci.web.api.job :as sut]
            [monkey.ci.web.response :as wr]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]))

(deftest unblock-job
  (let [{st :storage :as rt} (trt/test-runtime)]
    (testing "returns status 404 when job not found"
      (is (= 404 (:status (sut/unblock-job (h/->req rt))))))

    (testing "fires `job/unblocked` event"
      (let [[org-id repo-id build-id job-id :as sid] (repeatedly 4 cuid/random-cuid)
            job {:id job-id}]
        (is (some? (st/save-org st {:id org-id
                                    :repos {repo-id {:id repo-id}}})))
        (is (some? (st/save-build st (zipmap [:org-id :repo-id :build-id] sid))))
        (is (some? (st/save-job st (take 3 sid) job)))
        (let [r (-> (h/->req rt)
                    (assoc-in [:parameters :path] (zipmap [:org-id :repo-id :build-id :job-id] sid))
                    (sut/unblock-job))]
          (is (= 202 (:status r)))
          (is (= [:job/unblocked]
                 (->> (wr/get-events r)
                      (map :type)))))))))
