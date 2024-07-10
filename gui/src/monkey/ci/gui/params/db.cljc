(ns monkey.ci.gui.params.db
  (:require [monkey.ci.gui.utils :as u]))

(def loading? ::loading)

(defn mark-loading [db]
  (assoc db loading? true))

(defn unmark-loading [db]
  (dissoc db loading?))

(def saving? ::saving)

(defn mark-saving [db]
  (assoc db saving? true))

(defn unmark-saving [db]
  (dissoc db saving?))

(def params ::params)

(defn set-params [db p]
  (assoc db params (vec p)))

(def edit-params ::edit-params)

(defn set-edit-params [db p]
  (assoc db edit-params (vec p)))

(defn update-edit-params [db f & args]
  (apply update db edit-params f args))

(defn update-edit-param-set [db idx f & args]
  (update-edit-params db (comp vec #(apply u/update-nth % idx f args))))

(defn update-edit-param [db set-idx param-idx f & args]
  (update-edit-param-set db set-idx update :parameters u/update-nth param-idx #(apply f % args)))

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(defn clear-alerts [db]
  (dissoc db alerts))
