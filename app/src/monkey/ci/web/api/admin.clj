(ns monkey.ci.web.api.admin
  (:require [monkey.ci
             [cuid :as cuid]
             [storage :as s]]
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
  "Checks any OCI containers that have been running for too long, and kills them.
   Any associated builds and jobs will be canceled."
  [req]
  ;; TODO
  (rur/status 202))
