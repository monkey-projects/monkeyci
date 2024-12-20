(ns monkey.ci.web.api.admin-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [cuid :as cuid]
             [storage :as st]
             [time :as t]]
            [monkey.ci.web.api.admin :as sut]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.runtime :as trt]))

(deftest issue-credits
  (h/with-memory-store st
    (let [cust (h/gen-cust)]
      (testing "creates new customer credits in db"
        (let [user (h/gen-user)
              resp (-> {:storage st}
                       (h/->req)
                       (h/with-identity user)
                       (assoc :parameters
                              {:path
                               {:customer-id (:id cust)}
                               :body
                               {:amount 1000M}})
                       (sut/issue-credits))]
          (is (= 201 (:status resp)))
          (is (cuid/cuid? (get-in resp [:body :id])))
          (is (= (:id user) (get-in resp [:body :user-id]))))))))

(deftest issue-auto-credits
  (h/with-memory-store st
    (let [now      (t/now)
          until    (+ now 1000)
          cust     (h/gen-cust)
          sub      (-> (h/gen-credit-subs)
                       (assoc :customer-id (:id cust)
                              :amount 200M
                              :valid-from (- now 1000)
                              :valid-until until))
          issue-at (fn [at]
                     (-> {:storage st}
                         (h/->req)
                         (assoc-in [:parameters :body :from-time] at)
                         (sut/issue-auto-credits)))]
      (is (st/save-customer st cust))
      (is (st/save-credit-subscription st sub))
      (is (not-empty (st/list-active-credit-subscriptions st now)))

      (let [resp (issue-at now)]
        (is (= 200 (:status resp)))
        (is (not-empty (get-in resp [:body :credits]))))
      
      (testing "creates credit for each subscription"
        (let [cc (st/list-customer-credits-since st (:id cust) now)]
          (is (= 1 (count cc)))
          (is (= [200M] (map :amount cc)))))

      (testing "does not create credit if a future one already exists"
        (is (empty? (-> (issue-at now)
                        :body
                        :credits))))

      (testing "skips expired subscriptions"
        (is (empty? (-> (issue-at (+ until 1000))
                        :body
                        :credits)))))))

(deftest cancel-dangling-builds
  (testing "invokes process reaper"
    (let [inv (atom 0)
          rt (-> (trt/test-runtime)
                 (trt/set-process-reaper (fn []
                                           (swap! inv inc)
                                           [])))]
      (is (= 200 (-> rt
                     (h/->req)
                     (sut/cancel-dangling-builds)
                     :status)))
      (is (= 1 @inv))))

  (testing "dispatches `build/canceled` event for each reaped build process"
    (let [sid (repeatedly 3 cuid/random-cuid)
          rt (-> (trt/test-runtime)
                 (trt/set-process-reaper
                  (constantly [(zipmap [:customer-id :repo-id :build-id] sid)])))
          resp (-> rt
                   (h/->req)
                   (sut/cancel-dangling-builds))]
      (is (= 200 (:status resp)))
      (let [[f :as recv] (h/received-events (:events rt))]
        (is (not-empty recv))
        (is (= :build/canceled (:type f)))
        (is (= sid (:sid f))))
      (is (= [sid] (:body resp))))))
