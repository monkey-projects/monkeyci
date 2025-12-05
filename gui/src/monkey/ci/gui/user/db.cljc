(ns monkey.ci.gui.user.db
  (:require [monkey.ci.gui.login.db :as udb]))

(def get-user-settings ::user-settings)

(defn set-user-settings [db s]
  (assoc db ::user-settings s))

(def get-general-edit ::general-edit)

(defn set-general-edit [db v]
  (assoc db ::general-edit v))

(defn reset-general-edit [db]
  (dissoc db ::general-edit))

(defn update-general-edit [db f & args]
  (apply update db ::general-edit f args))

(def default-settings {:receive-mailing true})

(defn get-general-edit-merged
  "Combines user and default values with overwritten settings"
  [db]
  (merge (udb/user db) default-settings (get-general-edit db)))

(def get-general-alerts ::general-alerts)

(defn set-general-alerts [db a]
  (assoc db ::general-alerts a))

(defn reset-general-alerts [db]
  (dissoc db ::general-alerts))

(def general-saving? (comp true? ::general-saving))

(defn set-general-saving [db]
  (assoc db ::general-saving true))

(defn reset-general-saving [db]
  (dissoc db ::general-saving))
