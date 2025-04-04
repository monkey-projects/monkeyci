(ns monkey.ci.web.api.admin
  (:require [clojure.tools.logging :as log]
            [java-time.api :as jt]
            [monkey.ci
             [build :as b]
             [cuid :as cuid]
             [storage :as s]
             [time :as t]]
            [monkey.ci.common.preds :as cp]
            [monkey.ci.events
             [core :as ec]
             [mailman :as em]]
            [monkey.ci.web
             [auth :as auth]
             [common :as c]]
            [ring.util.response :as rur]))

(defn login
  "Authenticates admin user by looking up the user of type `sysadmin` with given
   username that matches given password."
  [req]
  (let [{:keys [username password]} (c/body req)
        st (c/req->storage req)
        sid [auth/role-sysadmin username]
        u (s/find-user-by-type st sid)
        sa (when u
             (s/find-sysadmin st (:id u)))]
    (if (and sa
             (= (:password sa) (auth/hash-pw password)))
      (rur/response (assoc u :token (->> (auth/sysadmin-token sid)
                                         (auth/generate-jwt-from-rt (c/req->rt req)))))
      ;; Invalid credentials
      (rur/status 403))))

(defn list-customer-credits
  "Returns overview of the issued credits to a customer"
  [req]
  (let [cust-id (c/customer-id req)
        creds (s/list-customer-credits (c/req->storage req) cust-id)]
    (rur/response creds)))

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

(defn issue-credits-for-subs
  "Given a customer id and list of subscriptions, issues any credits for the given 
   timestamp.  Returns a list of issued customer credit sids."
  [st ts [cust-id cust-subs]]
  (log/debug "Found" (count cust-subs) "subscriptions for customer" cust-id)
  (let [credits (->> (s/list-customer-credits-since st cust-id (- ts 100))
                     ;; TODO Filter in the query
                     (filter (cp/prop-pred :type :subscription))
                     (group-by :subscription-id))]
    (letfn [(issue-credits-for-sub [sub]
              (let [sc (->> (get credits (:id sub))
                            (filter (comp (partial t/same-date? ts) :from-time)))]
                (when (empty? sc)
                  (log/info "Creating new customer credit for sub" (:id sub) ", amount" (:amount sub))
                  (s/save-customer-credit st (-> sub
                                                 (select-keys [:customer-id :amount])
                                                 (assoc :id (cuid/random-cuid)
                                                        :type :subscription
                                                        :subscription-id (:id sub)
                                                        :from-time ts))))))]
      (->> cust-subs
           (map issue-credits-for-sub)
           (remove nil?)
           (doall)))))

(defn issue-auto-credits
  "Issues new credits to all customers that have active subscriptions that match
   the specified date.  This means all subscriptions where the `valid-from` date
   has the same day-of-month.  To avoid issuing credits multiple times, credits 
   are only issued if none exist for that subscription with the same month/year
   as the date specified in the request.

   The intention is that this endpoint is invoked once per day.  Should a call 
   fail, we can easily retry it using the same date."
  [req]
  (let [date (-> (get-in req [:parameters :body :date])
                 (jt/local-date))
        ts (-> (jt/instant date (jt/zone-id "UTC"))
               (t/day-start)
               (jt/to-millis-from-epoch))
        st (c/req->storage req)
        last-dom (-> date
                     (jt/adjust :last-day-of-month)
                     (jt/as :day-of-month))
        cur-dom (jt/as date :day-of-month)]
    (letfn [(should-process? [time]
              ;; Process the subscription if it's on the same day of month, or
              ;; this month does not have that many days.
              (or (t/same-dom? time ts)
                  (let [dom (jt/as (t/epoch->date time) :day-of-month)]
                    (and (< last-dom dom)
                         (= cur-dom last-dom)))))]
      ;; TODO Allow filtering by customer, if specified
      (log/info "Auto-issuing new credits for date" date " (timestamp" ts ")")
      ;; List all subscriptions that have become active on that day, so move ts to end of day
      (->> (s/list-active-credit-subscriptions st (+ ts (t/hours->millis 24)))
           ;; TODO Filter in the query instead of here
           (filter (comp should-process? :valid-from))
           (group-by :customer-id)
           (mapcat (partial issue-credits-for-subs st ts))
           ;; Return list of issued credit ids
           (map last)
           (hash-map :credits)
           (rur/response)))))

(defn cancel-dangling-builds
  "Checks any processes that have been running for too long, and kills them.
   Any associated builds and jobs will be canceled."
  [req]
  (let [rt (c/req->rt req)]
    (letfn [(to-event [b]
              ;; TODO Only cancel build if not already canceled or otherwise ended
              (ec/make-event :build/canceled {:sid (b/sid b)
                                              :src :reaper}))
            (dispatch [evts]
              (em/post-events (:mailman rt) evts)
              (map :sid evts))]
      (if-let [pr (:process-reaper rt)]
        (->> (pr)
             (map to-event)
             (dispatch)
             (rur/response))
        (rur/response {:message "No process reaper configured"})))))

(defn- req->subscription-sid [req]
  [(c/customer-id req)
   (get-in req [:parameters :path :subscription-id])])

(c/make-entity-endpoints
 "credit-subscription"
 {:get-id req->subscription-sid
  :getter s/find-credit-subscription})

(defn create-credit-subscription [req]
  (let [creator (c/entity-creator s/save-credit-subscription c/default-id)]
    (-> req
        (update-in [:parameters :body] assoc :customer-id (c/customer-id req))
        (creator))))

(defn list-credit-subscriptions [req]
  (-> req
      (c/req->storage)
      (s/list-customer-credit-subscriptions (c/customer-id req))
      (rur/response)))

(defn cancel-credit-subscription
  "Disables a credit subscription by setting the `valid-until` timestamp.  If a subscription
   is not active yet (i.e. the `valid-from` time is in the future), it is deleted instead."
  [req]
  (let [st (c/req->storage req)
        sid (req->subscription-sid req)
        until (or (get-in req [:parameters :body :valid-until]) (t/now))]
    (if-let [match (s/find-credit-subscription st sid)]
      (if (< until (:valid-from match))
        (do
          (s/delete-credit-subscription st sid)
          (rur/status 204))
        (let [upd (assoc match :valid-until until)]
          (s/save-credit-subscription st upd)
          (rur/response upd)))
      ;; Subscription or customer not found
      (rur/status 404))))
