(ns monkey.ci.web.middleware-test
  (:require [clojure.test :refer [deftest is testing]]
            [monkey.ci.test
             [helpers :as h]
             [mailman :as tmm]
             [runtime :as trt]]
            [monkey.ci.web
             [middleware :as sut]
             [response :as r]]
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

(deftest resolve-org-id
  (testing "invokes target handler"
    (let [r (sut/resolve-org-id (constantly ::handled) ::resolver)]
      (is (= ::handled (r {})))))

  (testing "replaces org id with resolved id"
    (let [r (sut/resolve-org-id #(get-in % [:parameters :path :org-id])
                                (fn [req org-id]
                                  (when (= "original-id" org-id)
                                    "resolved-id")))]
      (is (= "resolved-id"
             (r {:parameters
                 {:path
                  {:org-id "original-id"}}}))))))

(deftest replace-body-org-id
  (testing "invokes target"
    (let [r (sut/replace-body-org-id (constantly ::handled))]
      (is (= ::handled (r {})))))

  (testing "replaces `org-id` in body with path param"
    (let [r (sut/replace-body-org-id (comp :org-id :body :parameters))]
      (is (= "replaced" (-> {:parameters
                             {:body
                              {:org-id "original"}
                              :path
                              {:org-id "replaced"}}}
                            (r))))))

  (testing "does not add body when none present"
    (let [r (sut/replace-body-org-id identity)]
      (is (not (contains?
                (-> {:parameters {:path {:org-id "test-org"}}}
                    (r)
                    :parameters)
                :body))))))
