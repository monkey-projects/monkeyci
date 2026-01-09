(ns monkey.ci.storage.sql.org-credit
  (:require [medley.core :as mc]
            [monkey.ci.entities
             [core :as ec]
             [org-credit :as ecc]]
            [monkey.ci.storage.sql.common :as sc]))

(defn- org-credit->db [cred]
  (sc/id->cuid cred))

(defn- db->org-credit [cred]
  (mc/filter-vals some? cred))

(defn- insert-org-credit [conn {:keys [subscription-id user-id] :as cred}]
  (let [org (ec/select-org conn (ec/by-cuid (:org-id cred)))
        cs   (when subscription-id
               (or (ec/select-credit-subscription conn (ec/by-cuid subscription-id))
                   (throw (ex-info "Subscription not found" cred))))
        user (when user-id
               (or (ec/select-user conn (ec/by-cuid user-id))
                   (throw (ex-info "User not found" cred))))]
    (ec/insert-org-credit conn (-> cred
                                   (org-credit->db)
                                   (assoc :org-id (:id org)
                                          :subscription-id (:id cs)
                                          :user-id (:id user))))))

(defn- update-org-credit [conn cred existing]
  (ec/update-org-credit conn (merge existing (select-keys cred [:amount :valid-from :valid-until]))))

(defn upsert-org-credit [conn cred]
  (if-let [existing (ec/select-org-credit conn (ec/by-cuid (:id cred)))]
    (update-org-credit conn cred existing)
    (insert-org-credit conn cred)))

(defn select-org-credit [conn id]
  (some->> (ecc/select-org-credits conn (ecc/by-cuid id))
           (first)
           (db->org-credit)))

(defn select-org-credits-since [st org-id since]
  (->> (ecc/select-org-credits (sc/get-conn st) (ecc/by-org-since org-id since))
       (map db->org-credit)))

(defn select-org-credits [st org-id]
  (->> (ecc/select-org-credits (sc/get-conn st) (ecc/by-org org-id))
       (map db->org-credit)))

(defn select-avail-credits-amount [st org-id ts]
  ;; TODO Use the available-credits table for faster lookup
  (ecc/select-avail-credits-amount (sc/get-conn st) org-id ts))

(defn select-avail-credits [st org-id]
  (->> (ecc/select-avail-credits (sc/get-conn st) org-id)
       (map db->org-credit)))
