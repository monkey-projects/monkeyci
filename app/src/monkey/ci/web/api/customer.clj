(ns monkey.ci.web.api.customer
  "Specific customer api routes"
  (:require [monkey.ci
             [storage :as st]
             [time :as t]]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(defn recent-builds [req]
  (let [st (c/req->storage req)
        cid (c/customer-id req)]
    (if-let [cust (st/find-customer st cid)]
      (rur/response (st/list-builds-since st cid (- (t/now) (t/hours->millis 24))))
      (rur/not-found {:message "Customer not found"}))))

(defn stats
  "Retrieves customer statistics"
  [req]
  (rur/response {:period {:start (t/now)
                          :end (t/now)}
                 :stats []}))
