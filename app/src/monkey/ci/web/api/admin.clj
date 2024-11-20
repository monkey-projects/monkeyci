(ns monkey.ci.web.api.admin
  (:require [monkey.ci
             [build :as b]
             [cuid :as cuid]
             [storage :as s]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.web
             [auth :as auth]
             [common :as c]]
            [ring.util.response :as rur]))

(defn issue-credits
  "Issues ad-hoc credits to a customer."
  [req]
  (let [st (c/req->storage req)
        cid (c/customer-id req)
        creds (-> (c/body req)
                  (assoc :id (cuid/random-cuid)
                         :customer-id cid
                         :type :user
                         :user-id (auth/user-id req)))]
    (if-let [sid (s/save-customer-credit st creds)]
      (rur/created (last sid) creds)
      (rur/status 500))))

(defn issue-auto-credits
  "Issues new credits to all customers that have active subscriptions at the
   specified time.  The time should be in the future, and credits will only
   be created if none exist for that time yet.  This avoids creating multiple
   credits on multiple calls."
  [req]
  (let [at (get-in req [:parameters :body :from-time])
        st (c/req->storage req)]
    (letfn [(maybe-create-credit [sub]
              (let [credits (s/list-customer-credits-since st (:customer-id sub) at)]
                (when (empty? credits)
                  (s/save-customer-credit st (-> sub
                                                 (select-keys [:customer-id :amount])
                                                 (assoc :id (cuid/random-cuid)
                                                        :type :subscription
                                                        :subscription-id (:id sub)
                                                        :from-time at))))))]
      (->> (s/list-active-credit-subscriptions st at)
           (map maybe-create-credit)
           (doall)
           (remove nil?)
           (map last)
           (hash-map :credits)
           (rur/response)))))

(defn cancel-dangling-builds
  "Checks any processes that have been running for too long, and kills them.
   Any associated builds and jobs will be canceled."
  [req]
  (let [rt (c/req->rt req)]
    (letfn [(to-event [b]
              (ec/make-event :build/canceled {:sid (b/sid b)
                                              :src :reaper}))
            (dispatch [evts]
              (ec/post-events (:events rt) evts)
              (map :sid evts))]
      (if-let [pr (:process-reaper rt)]
        (->> (pr)
             (map to-event)
             (dispatch)
             (rur/response))
        (rur/response {:message "No process reaper configured"})))))
