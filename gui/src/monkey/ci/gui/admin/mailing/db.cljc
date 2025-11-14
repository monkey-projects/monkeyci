(ns monkey.ci.gui.admin.mailing.db
  (:require [monkey.ci.gui.loader :as l]))

(def mailing-id ::id)

(defn get-mailings [db]
  (l/get-value db mailing-id))

(defn set-mailings [db m]
  (l/set-value db mailing-id m))

(defn update-mailing [db upd]
  (if-let [match (->> (get-mailings db)
                      (filter (comp (partial = (:id upd)) :id))
                      (first))]
    (l/update-value db mailing-id (partial replace {match upd}))
    (l/update-value db mailing-id (comp vec conj) upd)))

(defn get-editing [db]
  (::editing db))

(defn set-editing [db m]
  (assoc db ::editing m))

(defn reset-editing [db]
  (dissoc db ::editing))

(defn saving? [db]
  (true? (::saving db)))

(defn mark-saving [db]
  (assoc db ::saving true))

(defn reset-saving [db]
  (dissoc db ::saving))
