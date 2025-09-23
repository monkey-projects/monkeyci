(ns monkey.ci.storage.sql.common
  (:require [medley.core :as mc]))

(def deleted? (fnil pos? 0))

(defn drop-nil [m]
  (mc/filter-vals some? m))

(defn get-conn [c]
  ((:get-conn c) c))

(defn db->labels [labels]
  (map #(select-keys % [:name :value]) labels))

(defn id->cuid [x]
  (-> x
      (assoc :cuid (:id x))
      (dissoc :id)))

(defn cuid->id [x]
  (-> x
      (assoc :id (:cuid x))
      (dissoc :cuid)))
