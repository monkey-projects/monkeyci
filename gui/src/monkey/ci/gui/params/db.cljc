(ns monkey.ci.gui.params.db
  (:require [monkey.ci.gui.utils :as u]))

(def loading? ::loading)

(defn mark-loading [db]
  (assoc db loading? true))

(defn unmark-loading [db]
  (dissoc db loading?))

(defn mark-saving [db id]
  (assoc-in db [::saving id] true))

(defn unmark-saving [db id]
  (update db ::saving dissoc id))

(defn saving? [db id]
  (some? (get-in db [::saving id])))

(def params ::params)

(defn set-params [db p]
  (assoc db params (vec p)))

(defn set-deleting? [db id]
  (true? (get-in db [::set-deleting id])))

(defn mark-set-deleting [db id]
  (assoc-in db [::set-deleting id] true))

(defn unmark-set-deleting [db id]
  (update db ::set-deleting dissoc id))

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(defn clear-alerts [db]
  (dissoc db alerts))

(defn set-set-alerts [db id a]
  (assoc-in db [::set-alerts id] a))

(defn get-set-alerts [db id]
  (get-in db [::set-alerts id]))

(defn clear-set-alerts [db id]
  (update db ::set-alerts dissoc id))

(def edit-sets ::edit-sets)

(defn set-editing [db id props]
  (assoc-in db [edit-sets id] props))

(defn update-editing [db id f & args]
  (apply update-in db [edit-sets id] f args))

(defn update-editing-param [db id param-idx f & args]
  (update-editing
   db id
   update :parameters u/update-nth param-idx #(apply f % args)))

(defn unset-editing [db id]
  (update db edit-sets dissoc id))

(defn clear-editing [db]
  (dissoc db edit-sets))

(defn get-editing [db id]
  (get-in db [edit-sets id]))

(defn editing? [db id]
  (some? (get-editing db id)))

(defn new-temp-id
  "Generates a new id, assigned to new parameter sets"
  []
  (random-uuid))

(defn temp-id? [x]
  (uuid? x))
