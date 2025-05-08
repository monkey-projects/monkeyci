(ns monkey.ci.web.api.admin-test
  (:require [clojure.test :refer [deftest is testing]]
            [java-time.api :as jt]
            [monkey.ci
             [cuid :as cuid]
             [sid :as sid]
             [storage :as st]
             [time :as t]]
            [monkey.ci.test
             [helpers :as h]
             [mailman :as tm]
             [runtime :as trt]]
            [monkey.ci.web.api.admin :as sut]
            [monkey.ci.web.auth :as auth]))

(deftest login
  (let [{st :storage :as rt} (trt/test-runtime)
        u (-> (h/gen-user)
              (assoc :type "sysadmin"))
        s {:user-id (:id u)
           :password (auth/hash-pw "test-pass")}]
    (is (sid/sid? (st/save-user st u)))
    (is (sid/sid? (st/save-sysadmin st s)))
    
    (testing "returns token for valid credentials"
      (let [r (-> rt
                  (h/->req)
                  (assoc-in [:parameters :body]
                            {:username (:type-id u)
                             :password "test-pass"})
                  (sut/login))]
        (is (= 200 (:status r)))
        (is (string? (get-in r [:body :token])))
        (is (= (:id u) (get-in r [:body :id])))))

    (testing "403 if user does not exist"
      (is (= 403 (-> rt
                     (h/->req)
                     (assoc-in [:parameters :body :username] "nonexisting")
                     (sut/login)
                     :status))))

    (testing "403 if invalid password"
      (is (= 403 (-> rt
                     (h/->req)
                     (assoc-in [:parameters :body]
                               {:username (:type-id u)
                                :password "wrong-pass"})
                     (sut/login)
                     :status))))))

(deftest list-org-credits
  (let [{st :storage :as rt} (trt/test-runtime)
        org (h/gen-org)
        cred (-> (h/gen-org-credit)
                 (assoc :org-id (:id org)))]
    (is (sid/sid? (st/save-org st org)))
    (is (sid/sid? (st/save-org-credit st cred)))
    
    (testing "returns list of issued credits for org"
      (let [resp (-> rt
                     (h/->req)
                     (assoc-in [:parameters :path :org-id] (:id org))
                     (sut/list-org-credits))]
        (is (= 200 (:status resp)))
        (is (not-empty (:body resp)))))))

(deftest issue-credits
  (h/with-memory-store st
    (let [org (h/gen-org)]
      (testing "creates new org credits in db"
        (let [user (h/gen-user)
              resp (-> {:storage st}
                       (h/->req)
                       (h/with-identity user)
                       (assoc :parameters
                              {:path
                               {:org-id (:id org)}
                               :body
                               {:amount 1000M}})
                       (sut/issue-credits))]
          (is (= 201 (:status resp)))
          (is (cuid/cuid? (get-in resp [:body :id])))
          (is (= (:id user) (get-in resp [:body :user-id]))))))))

(deftest issue-auto-credits
  (h/with-memory-store st
    (letfn [(ts->date [ts]
              (-> (jt/instant ts)
                  (jt/local-date (jt/zone-id "UTC"))))
            (ts->date-str [ts]
              (-> (ts->date ts)
                  (jt/format)))
            (issue-at [at]
              (-> {:storage st}
                  (h/->req)
                  (assoc-in [:parameters :body :date] at)
                  (sut/issue-auto-credits)))]
      (let [now   (jt/to-millis-from-epoch (jt/zoned-date-time 2025 3 10 10 0 0 0 "UTC"))
            today (ts->date-str now)
            from  (- now 1000)
            until (+ now (t/hours->millis (* 24 40)))
            org  (h/gen-org)
            sub   (-> (h/gen-credit-subs)
                      (assoc :org-id (:id org)
                             :amount 200M
                             :valid-from from
                             :valid-until until))]
        
        (is (st/save-org st org))
        (is (st/save-credit-subscription st sub))
        (is (not-empty (st/list-active-credit-subscriptions st now)))

        (let [resp (issue-at today)]
          (is (= 200 (:status resp)))
          (is (not-empty (get-in resp [:body :credits]))
              "expected credits to have been issued"))
        
        (testing "creates credit for each subscription"
          (let [cc (st/list-org-credits-since st (:id org) 0)]
            (is (= 1 (count cc)))
            (is (= [200M] (map :amount cc)))))

        (testing "does not issue credits if subscription date is on another day of the month"
          (is (empty? (-> (issue-at (ts->date-str (+ now (t/hours->millis 24))))
                          :body
                          :credits))))

        (testing "does not create credit if one already exists for that subscription and date"
          (is (empty? (-> (issue-at today)
                          :body
                          :credits))))

        (testing "creates credit for same day of month"
          (is (not-empty (-> (ts->date now)
                             (jt/plus (jt/months 1))
                             (issue-at)
                             :body
                             :credits))))

        (testing "when month has fewer days, also creates credits for missing days"
          (let [org (h/gen-org)
                date (-> (jt/offset-date-time 2024 1 30)
                         (jt/with-offset 0) ; force UTC
                         (jt/to-millis-from-epoch))
                cs {:id (cuid/random-cuid)
                    :valid-from date
                    :valid-until (+ date (t/hours->millis (* 24 100)))
                    :org-id (:id org)
                    :amount 100}]
            (is (some? (st/save-org st org)))
            (is (some? (st/save-credit-subscription st cs)))
            (is (not-empty (->> (issue-at "2024-02-29")
                                :body
                                :credits)))))

        (testing "does not process when month has max days"
          (let [org (h/gen-org)
                date (-> (jt/offset-date-time 2025 3 31)
                         (jt/with-offset 0) ; force UTC
                         (jt/to-millis-from-epoch))
                cs {:id (cuid/random-cuid)
                    :valid-from date
                    :valid-until (+ date (t/hours->millis (* 24 100)))
                    :org-id (:id org)
                    :amount 150}]
            (is (some? (st/save-org st org)))
            (is (some? (st/save-credit-subscription st cs)))
            (is (empty? (->> (issue-at "2024-04-01")
                             :body
                             :credits)))))
        
        (testing "ignores ad-hoc credit issuances"
          (is (st/save-org-credit st {:org-id (:id org)
                                      :type :user
                                      :amount 1000M
                                      :valid-from (+ now 2000)}))
          (is (st/save-credit-subscription st (-> (h/gen-credit-subs)
                                                  (assoc :org-id (:id org)
                                                         :amount 300M
                                                         :valid-from from
                                                         :valid-until until))))
          (is (not-empty (->> (issue-at (ts->date-str now)) 
                              :body
                              :credits))))

        (testing "skips expired subscriptions"
          (let [ids (-> (issue-at (ts->date-str (+ until (t/hours->millis 20))))
                        :body
                        :credits)]
            (is (empty? ids))))))))

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
                  (constantly [(zipmap [:org-id :repo-id :build-id] sid)])))
          resp (-> rt
                   (h/->req)
                   (sut/cancel-dangling-builds))]
      (is (= 200 (:status resp)))
      (let [[f :as recv] (tm/get-posted (:mailman rt))]
        (is (not-empty recv))
        (is (= :build/canceled (:type f)))
        (is (= sid (:sid f))))
      (is (= [sid] (:body resp))))))

(deftest cancel-credit-subscription
  (testing "deletes when `valid-from` time is in the future"
    (let [{st :storage :as rt} (trt/test-runtime)
          org (h/gen-org)
          cs {:id (cuid/random-cuid)
              :org-id (:id org)
              :valid-from (+ (t/now) 1000)
              :amount 1000}]
      (is (sid/sid? (st/save-org st org)))
      (is (sid/sid? (st/save-credit-subscription st cs)))
      (is (= 204 (-> (h/->req rt)
                     (assoc :parameters
                            {:path
                             {:org-id (:id org)
                              :subscription-id (:id cs)}})
                     (sut/cancel-credit-subscription)
                     :status)))
      (is (nil? (st/find-credit-subscription st [(:id org) (:id cs)]))))))
