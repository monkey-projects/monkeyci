(ns monkey.ci.entities.webhook
  "Webhook related functionality"
  (:require [honey.sql :as h]
            [monkey.ci.entities.core :as ec]))

(def base-query
  {:select [[:c.cuid :customer-id]
            [:r.display-id :repo-id]
            [:w.cuid :id]
            [:w.secret :secret-key]]
   :from [[:webhooks :w]]
   :join [[:customers :c] [:= :c.id :r.customer-id]
          [:repos :r] [:= :r.id :w.repo-id]]})

(defn select-webhooks-as-entity
  "Select the necessary properties for a webhook to return it as an entity."
  [conn f]
  (ec/select conn
             (assoc base-query
                    :where f)))

(defn by-cuid [cuid]
  [:= :w.cuid cuid])

(defn by-repo [cust-id repo-id]
  [:and
   [:= :c.cuid cust-id]
   [:= :r.display-id repo-id]])
