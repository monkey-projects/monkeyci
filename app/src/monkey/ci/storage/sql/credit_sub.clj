(ns monkey.ci.storage.sql.credit-sub
  (:require [medley.core :as mc]
            [monkey.ci.entities
             [core :as ec]
             [credit-subs :as ecsub]]
            [monkey.ci.storage.sql.common :as sc]))

(defn- credit-sub->db [cs]
  (sc/id->cuid cs))

(defn- db->credit-sub [cs]
  (mc/filter-vals some? cs))

(defn- insert-credit-subscription [conn cs]
  (let [org (ec/select-org conn (ec/by-cuid (:org-id cs)))]
    (ec/insert-credit-subscription conn (assoc (credit-sub->db cs)
                                               :org-id (:id org)))))

(defn- update-credit-subscription [conn cs existing]
  (ec/update-credit-subscription conn (merge existing
                                             (-> (credit-sub->db cs)
                                                 (dissoc :org-id)))))

(defn upsert-credit-subscription [conn cs]
  (if-let [existing (ec/select-credit-subscription conn (ec/by-cuid (:id cs)))]
    (update-credit-subscription conn cs existing)
    (insert-credit-subscription conn cs)))

(defn delete-credit-subscription [conn cuid]
  (ec/delete-credit-subscriptions conn (ec/by-cuid cuid)))

(defn select-credit-subscription [conn cuid]
  (some->> (ecsub/select-credit-subs conn (ecsub/by-cuid cuid))
           (first)
           (db->credit-sub)))

(defn- select-credit-subs [conn f]
  (->> (ecsub/select-credit-subs conn f)
       (map db->credit-sub)))

(defn select-org-credit-subs [st org-id]
  (select-credit-subs (sc/get-conn st) (ecsub/by-org org-id)))

(defn select-active-credit-subs [st at]
  (select-credit-subs (sc/get-conn st) (ecsub/active-at at)))
