(ns monkey.ci.entities.webhook
  "Webhook related functionality"
  (:require [honey.sql :as h]
            [monkey.ci.entities.core :as ec]))

(def base-query
  {:select [[:c.cuid :org-id]
            [:r.display-id :repo-id]
            [:w.cuid :id]
            [:w.secret :secret-key]
            :w.creation-time
            :w.last-inv-time]
   :from [[:webhooks :w]]
   :join [[:repos :r] [:= :r.id :w.repo-id]
          [:orgs :c] [:= :c.id :r.org-id]]})

(defn select-webhooks-as-entity
  "Select the necessary properties for a webhook to return it as an entity."
  [conn f]
  (->> (ec/select conn
                  (assoc base-query
                         :where f))
       (map ec/convert-webhook-select)))

(defn by-cuid [cuid]
  [:= :w.cuid cuid])

(defn by-repo [org-id repo-id]
  [:and
   [:= :c.cuid org-id]
   [:= :r.display-id repo-id]])
