(ns monkey.ci.web.api.mailing-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.storage :as st]
            [monkey.ci.web.api.mailing :as sut]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]))

(deftest create-mailing
  (let [{st :storage :as rt} (trt/test-runtime)]
    (testing "assigns creation time"
      (let [r (-> rt
                  (h/->req)
                  (assoc-in [:parameters :body] {:subject "test mailing"})
                  (sut/create-mailing))]
        (is (= 201 (:status r)))
        (is (number? (->> r
                          :body
                          :id
                          (st/find-mailing st)
                          :creation-time)))))))
