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
  "Issues new credits to all customers that have subscriptions."
  [req]
  ;; TODO
  (rur/response {:message "todo"}))
