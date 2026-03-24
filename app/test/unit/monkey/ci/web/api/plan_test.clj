(ns monkey.ci.web.api.plan-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [storage :as st]
             [time :as t]]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]
            [monkey.ci.web.api.plan :as sut]))

(deftest create-plan
  (let [{st :storage :as rt} (trt/test-runtime)
        org (h/gen-org)
        old-sub (-> (h/gen-credit-subs)
                    (assoc :org-id (:id org) :valid-from 0)
                    (dissoc :valid-until))
        old-plan {:org-id (:id org)
                  :id (st/new-id)
                  :type :free
                  :subscription-id (:id old-sub)}]
    (is (some? (st/save-org st org)))
    (is (some? (st/save-credit-subscription st old-sub)))
    (is (some? (st/save-org-plan st old-plan)))
    
    (let [r (-> (h/->req rt)
                (assoc :parameters
                       {:path {:org-id (:id org)}
                        :body {:type :starter
                               :valid-from 1000}})
                (sut/create-plan))]
      (testing "creates new org plan"
        (is (= 201 (:status r)))
        (is (some? (st/find-org-plan st [(:id org) (get-in r [:body :id])]))))

      (testing "ends current org plan"
        (is (= 1000 (-> (st/find-org-plan st [(:id org) (:id old-plan)])
                        :valid-until))))

      (let [cs (st/list-org-credit-subscriptions st (:id org))]
        (testing "creates credit subscription"
          (is (= 2 (count cs)))
          (is (some? (get-in r [:body :subscription-id])))
          (is (= 1000 (->> (get-in r [:body :subscription-id])
                           (vector (:id org))
                           (st/find-credit-subscription st)
                           :valid-from))))

        (testing "ends previous subscription"
          (is (= 1000 (-> (st/find-credit-subscription st [(:id org) (:id old-sub)])
                          :valid-until))))))

    (testing "sets plan defaults"
      (let [r (-> (h/->req rt)
                  (assoc :parameters
                         {:path {:org-id (:id org)}
                          :body {:type :starter
                                 :valid-from 2000}})
                  (sut/create-plan))]
        (is (= 201 (:status r)))
        (is (= 5000
               (-> (st/find-org-plan st [(:id org) (get-in r [:body :id])])
                   :credits)))))))

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

(deftest cancel-plan
  (let [{st :storage :as rt} (trt/test-runtime)
        org (h/gen-org)
        sub (-> (h/gen-credit-subs)
                (assoc :org-id (:id org)))
        plan {:org-id (:id org)
              :id (st/new-id)
              :type :startup
              :valid-from 1000
              :subscription-id (:id sub)}
        at 2000]
    (is (some? (st/save-org st org)))
    (is (some? (st/save-credit-subscription st sub)))
    (is (some? (st/save-org-plan st plan)))
    (let [r (-> (h/->req rt)
                (assoc :parameters
                       {:path {:org-id (:id org)}
                        :body {:when at}})
                (sut/cancel-plan))]
      (is (= 200 (:status r)))
      
      (testing "ends current plan at specified time"
        (is (= at (-> (st/find-org-plan st [(:id org) (:id plan)])
                      :valid-until))))

      (testing "ends previous subscription"
        (is (= at (-> (st/find-credit-subscription st [(:id org) (:id sub)])
                      :valid-until))))

      (let [new-plan (st/find-org-plan st [(:id org) (get-in r [:body :id])])]
        (testing "returns new free plan valid from specified time"
          (is (some? new-plan))
          (is (= :free (:type new-plan)))
          (is (= at (:valid-from new-plan))))

        (testing "creates new subscription"
          (is (= 2 (count (st/list-org-credit-subscriptions st (:id org)))))
          (is (= at (-> (st/find-credit-subscription st [(:id org) (:subscription-id new-plan)])
                        :valid-from))))))))

(deftest validate-plan
  (testing "starter"
    (let [v (sut/validate-plan {:type :starter})]
      (testing "sets max users"
        (is (= 3 (:max-users v))))
      
      (testing "sets credits"
        (is (= 5000 (:credits v))))))

  (testing "pro"
    (testing "sets credits"
      (is (= 30000 (-> (sut/validate-plan {:type :pro :max-users 10})
                       :credits))))

    (testing "fails when no users"
      (is (thrown? Exception (sut/validate-plan {:type :pro}))))))
