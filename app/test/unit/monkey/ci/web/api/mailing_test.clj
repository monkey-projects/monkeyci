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

(deftest list-sent-mailings
  (testing "lists sent mailings for mailing"
    (let [m (h/gen-mailing)
          s (-> (h/gen-sent-mailing)
                (assoc :mailing-id (:id m)))
          {st :storage :as rt} (trt/test-runtime)]
      (is (some? (st/save-mailing st m)))
      (is (some? (st/save-sent-mailing st s)))
      (let [r (-> rt
                  (h/->req)
                  (assoc-in [:parameters :path :mailing-id] (:id m))
                  (sut/list-sent-mailings))]
        (is (= 200 (:status r)))
        (is (= [s] (:body r)))))))

#_(deftest create-sent-mailing
  (testing "creates in storage")
  (testing "sends mails"))
