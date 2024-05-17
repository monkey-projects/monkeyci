(ns monkey.ci.entities.webhook
  "Webhook related functionality"
  (:require [honey.sql :as h]
            [monkey.ci.entities.core :as ec]))

(defn select-webhook-as-entity
  "Select the necessary properties for a webhook to return it as an entity."
  [conn uuid]
  (ec/select conn
             {:select [[:c.uuid :customer-id]
                       [:r.display-id :repo-id]
                       [:w.uuid :id]
                       [:w.secret :secret-key]]
              :from [[:customers :c]
                     [:repos :r]
                     [:webhooks :w]]
              :where [:and
                      [:= :w.uuid uuid]
                      [:= :r.id :w.repo-id]
                      [:= :c.id :r.customer-id]]}))

