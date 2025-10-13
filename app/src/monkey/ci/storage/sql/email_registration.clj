(ns monkey.ci.storage.sql.email-registration
  (:require [monkey.ci.entities.core :as ec]
            [monkey.ci.storage.sql.common :as sc]))

(defn- db->email-registration [reg]
  (sc/cuid->id reg))

(defn select-email-registration [conn cuid]
  (some-> (ec/select-email-registration conn (ec/by-cuid cuid))
          (db->email-registration)))

(defn select-email-registration-by-email [st email]
  (some-> (ec/select-email-registration (sc/get-conn st) [:= :email email])
          (db->email-registration)))

(defn select-email-registrations [st]
  (->> (ec/select-email-registrations (sc/get-conn st) nil)
       (map db->email-registration)))

(defn insert-email-registration [conn reg]
  ;; Updates not supported
  (ec/insert-email-registration conn (-> reg
                                         (assoc :cuid (:id reg))
                                         (dissoc :id))))

(defn delete-email-registration [conn cuid]
  (ec/delete-email-registrations conn (ec/by-cuid cuid)))

(defn count-email-registrations [st]
  (ec/count-entities (sc/get-conn st) :email-registrations))
