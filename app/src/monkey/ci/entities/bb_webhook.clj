(ns monkey.ci.entities.bb-webhook
  (:require [monkey.ci.entities.core :as ec]))

(defn- db->entity [r]
  (-> r
      (dissoc :wh-cuid)
      (assoc :webhook-id (:wh-cuid r))))

(defn select-bb-webhook
  "Selects bitbucket webhook and associated webhook cuid"
  [conn f]
  (some->> {:select [:bb.* [:wh.cuid :wh-cuid]]
            :from [[:bb-webhooks :bb]]
            :join [[:webhooks :wh] [:= :wh.id :bb.webhook-id]]
            :where f}
           (ec/select conn)
           (first)
           db->entity))

(defn by-cuid [id]
  [:= :bb.cuid id])

(defn by-wh-cuid [id]
  [:= :wh.cuid id])
