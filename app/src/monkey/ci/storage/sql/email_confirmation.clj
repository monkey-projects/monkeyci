(ns monkey.ci.storage.sql.email-confirmation
  (:require [monkey.ci.entities
             [core :as ec]
             [email-confirmation :as eec]]
            [monkey.ci.storage.sql.common :as sc]))

(defn- db->email-confirmation [c]
  (-> c
      (sc/cuid->id)
      (assoc :email-reg-id (:reg-cuid c))
      (dissoc :reg-cuid)))

(defn select-email-confirmation [conn cuid]
  (some-> (eec/select-email-confirmation conn cuid)
          (db->email-confirmation)))

(defn select-email-confirmations-by-reg [st reg-id]
  (->> (eec/select-email-confirmations-by-reg (sc/get-conn st) reg-id)
       (map db->email-confirmation)))

(defn insert-email-confirmation [conn c]
  (when-let [reg (ec/select-email-registration conn (ec/by-cuid (:email-reg-id c)))]
    ;; Updates not supported
    (ec/insert-email-confirmation conn (-> (sc/id->cuid c)
                                           (assoc :email-reg-id (:id reg))))))

(defn delete-email-confirmation [conn cuid]
  (ec/delete-email-confirmations conn (ec/by-cuid cuid)))
