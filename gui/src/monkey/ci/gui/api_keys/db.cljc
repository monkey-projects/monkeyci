(ns monkey.ci.gui.api-keys.db
  (:require [monkey.ci.gui.loader :as l]))

(def org-id ::org-tokens)

(defn set-org-tokens [db v]
  (l/set-value db org-id v))

(defn get-org-tokens [db]
  (l/get-value db org-id))

(defn set-org-tokens-loading [db]
  (l/set-loading db org-id))

(defn reset-org-tokens-loading [db]
  (l/reset-loading db org-id))

(defn get-org-tokens-loading [db]
  (l/loading? db org-id))

(defn set-token-edit [db id v]
  (assoc-in db [id ::edit] v))

(defn reset-token-edit [db id]
  (update db id dissoc ::edit))

(defn update-token-edit [db id f & args]
  (apply update-in db [id ::edit] f args))

(defn get-token-edit [db id]
  (get-in db [id ::edit]))

(defn set-new-token [db id v]
  (assoc-in db [id ::new-token] v))

(defn get-new-token [db id]
  (get-in db [id ::new-token]))

(def get-alerts l/get-alerts)
(def set-alerts l/set-alerts)
