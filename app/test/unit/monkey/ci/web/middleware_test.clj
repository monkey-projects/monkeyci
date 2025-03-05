(ns monkey.ci.web.middleware-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [monkey.ci.test.helpers :as h]
   [monkey.ci.test.mailman :as tmm]
   [monkey.ci.test.runtime :as trt]
   [monkey.ci.web.middleware :as sut]
   [monkey.ci.web.response :as r]
   [ring.util.response :as rur]))

(deftest post-events
  (testing "returns handler response"
    (let [resp (rur/response "test reply")
          mw (sut/post-events (constantly resp))]
      (is (= resp (mw {})))))

  (testing "posts events found in the response"
    (let [evt {:type ::test-event}
          resp (-> (rur/response "test reply")
                   (r/add-event evt))
          rt (trt/test-runtime)
          req (h/->req rt)
          mw (sut/post-events (constantly resp))
          broker (trt/get-mailman rt)
          r (mw req)]
      (is (empty? (r/get-events r))
          "removes processed events")
      (is (= [evt] (tmm/get-posted broker))))))
