(ns monkey.ci.web.api.plan-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [storage :as st]
             [time :as t]]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]
            [monkey.ci.web.api.plan :as sut]))

(deftest get-current
  (let [{st :storage :as rt} (trt/test-runtime)
        org (h/gen-org)]
    (is (some? (st/save-org st org)))
    
    (testing "`204 no content` if no plans"
      (is (= 204
             (-> (h/->req rt)
                 (assoc-in [:parameters :path :org-id] (:id org))
                 (sut/get-current)
                 :status))))

    (let [plan {:org-id (:id org)
                :id (st/new-id)
                :type :startup
                :max-users 3
                :valid-from (- (t/now) (t/hours->millis 24))
                :valid-until (- (t/now) (t/hours->millis 4))}]
      (is (some? (st/save-org-plan st plan)))
      
      (testing "`204 no content` if no current plan"
        (is (= 204
               (-> (h/->req rt)
                   (assoc-in [:parameters :path :org-id] (:id org))
                   (sut/get-current)
                   :status))))

      (testing "returns current plan"
        (let [valid {:org-id (:id org)
                     :id (st/new-id)
                     :type :pro
                     :max-users 30
                     :valid-from (- (t/now) (t/hours->millis 4))}]
          (is (some? (st/save-org-plan st valid)))
          
          (let [r (-> (h/->req rt)
                      (assoc-in [:parameters :path :org-id] (:id org))
                      (sut/get-current))]
            (is (= 200 (:status r)))
            (is (= (:id valid) (get-in r [:body :id])))))))))

(deftest org-history
  (testing "returns all plans for org"
    (let [{st :storage :as rt} (trt/test-runtime)
          org (h/gen-org)]
      (is (some? (st/save-org st org)))
      (is (some? (st/save-org-plan st {:org-id (:id org)
                                       :id (st/new-id)
                                       :type :startup
                                       :valid-from 100})))
      (is (some? (st/save-org-plan st {:org-id (:id org)
                                       :id (st/new-id)
                                       :type :startup
                                       :valid-from 200})))
    
      (let [r (-> (h/->req rt)
                  (assoc-in [:parameters :path :org-id] (:id org))
                  (sut/org-history))]
        (is (= 200 (:status r)))
        (is (= 2 (count (:body r))))))))
