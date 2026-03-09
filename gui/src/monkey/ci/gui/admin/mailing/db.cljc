(ns monkey.ci.gui.admin.mailing.db
  (:require [monkey.ci.gui.loader :as l]))

(def mailing-id ::id)
(def editing-id ::editing)
(def sent-id ::sent)

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

(defn loading? [db]
  (l/loading? db mailing-id))

(defn get-alerts [db]
  (l/get-alerts db mailing-id))

(defn set-alerts [db a]
  (l/set-alerts db mailing-id a))

(defn get-editing [db]
  (::editing db))

(defn set-editing [db m]
  (assoc db ::editing m))

(defn update-editing [db f & args]
  (apply update db ::editing f args))

(defn reset-editing [db]
  (dissoc db ::editing))

(defn saving? [db]
  (true? (::saving db)))

(defn mark-saving [db]
  (assoc db ::saving true))

(defn reset-saving [db]
  (dissoc db ::saving))

(defn set-editing-alerts [db a]
  (l/set-alerts db editing-id a))

(defn get-editing-alerts [db]
  (l/get-alerts db editing-id))

(defn reset-editing-alerts [db]
  (l/reset-alerts db editing-id))

(defn get-sent-mailings [db]
  (l/get-value db sent-id))

(defn set-sent-mailings [db m]
  (l/set-value db sent-id m))

(defn update-sent-mailings [db f & args]
  (apply l/update-value db sent-id f args))

(defn get-sent-alerts [db]
  (l/get-alerts db sent-id))

(defn set-sent-alerts [db a]
  (l/set-alerts db sent-id a))

(defn sent-loading? [db]
  (l/loading? db sent-id))

(defn set-new-delivery [db d]
  (assoc db ::new-delivery d))

(defn reset-new-delivery [db]
  (dissoc db ::new-delivery))

(defn get-new-delivery [db]
  (::new-delivery db))

(defn update-new-delivery [db f & args]
  (apply update db ::new-delivery f args))

(defn mark-saving-new-delivery [db]
  (assoc db ::saving-new-delivery true))

(defn unmark-saving-new-delivery [db]
  (dissoc db ::saving-new-delivery))

(defn saving-new-delivery? [db]
  (true? (::saving-new-delivery db)))

