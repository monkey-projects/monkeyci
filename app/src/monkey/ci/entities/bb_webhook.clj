(ns monkey.ci.entities.bb-webhook
  (:require [monkey.ci.entities.core :as ec]))

(defn- db->entity [r]
  (-> r
      (dissoc :wh-cuid)
      (assoc :webhook-id (:wh-cuid r))))

(def base-query
  {:select [:bb.* [:wh.cuid :wh-cuid]]
   :from [[:bb-webhooks :bb]]
   :join [[:webhooks :wh] [:= :wh.id :bb.webhook-id]]})

(defn select-bb-webhooks
  "Selects bitbucket webhook and associated webhook cuid"
  [conn f]
  (->> (assoc base-query :where f)
       (ec/select conn)
       (map db->entity)))

(defn select-bb-webhooks-with-repos
  "Selects bitbucket webhook including customers and repos"
  [conn f]
  (->> (-> base-query
           (assoc :select [:bb.*
                           [:wh.cuid :wh-cuid]
                           [:c.cuid :customer-id]
                           [:r.display-id :repo-id]]
                  :where f)
           (update :join concat [[:repos :r] [:= :r.id :wh.repo-id]
                                 [:customers :c] [:= :c.id :r.customer-id]]))
       (ec/select conn)
       (map db->entity)))

(defn by-cuid [id]
  [:= :bb.cuid id])

(defn by-wh-cuid [id]
  [:= :wh.cuid id])

(defn- ->where [f table]
  (letfn [(->clause [k v]
            [:= (if table
                  (keyword (str (name table) "." (name k)))
                  k) v])]
    (reduce-kv (fn [r k v]
                 (conj r (->clause k v)))
               []
               f)))

(defn- prefix-and [v]
  (cond->> v
    (> (count v) 1) (concat [:and])))

(defn by-filter
  "Constructs where clause given a generic webhook filter"
  [f]
  (let [wh-props #{:customer-id :repo-id :webhook-id}
        bb-f (apply dissoc f wh-props)]
    (cond-> []
      (not-empty bb-f) (concat (->where bb-f :bb))
      (some? (:webhook-id f)) (conj [:= :wh.cuid (:webhook-id f)])
      (some? (:customer-id f)) (conj [:= :c.cuid (:customer-id f)])
      (some? (:repo-id f)) (conj [:= :r.display-id (:repo-id f)])
      true (prefix-and))))
