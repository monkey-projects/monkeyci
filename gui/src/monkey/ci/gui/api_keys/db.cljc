(ns monkey.ci.gui.api-keys.db
  (:require [monkey.ci.gui.loader :as l]))

(def org-id ::org-tokens)

(def get-tokens l/get-value)
(def set-tokens l/set-value)

(def update-tokens l/update-value)

(defn set-org-tokens [db v]
  (set-tokens db org-id v))

(defn get-org-tokens [db]
  (get-tokens db org-id))

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

(defn reset-new-token [db id]
  (update db id dissoc ::new-token))

(defn get-new-token [db id]
  (get-in db [id ::new-token]))

(def get-alerts l/get-alerts)
(def set-alerts l/set-alerts)

(defn set-saving [db id]
  (assoc-in db [id ::saving] true))

(defn reset-saving [db id]
  (update db id dissoc ::saving))

(defn saving? [db id]
  (true? (get-in db [id ::saving])))

(defn set-token-to-delete [db id t]
  (assoc-in db [id ::to-delete] t))

(defn reset-token-to-delete [db id]
  (update db id dissoc ::to-delete))

(defn get-token-to-delete [db id]
  (get-in db [id ::to-delete]))

(defn set-deleting [db id]
  (assoc-in db [id ::deleting] true))

(defn reset-deleting [db id]
  (update db id dissoc ::deleting))

(defn deleting? [db id]
  (true? (get-in db [id ::deleting])))
