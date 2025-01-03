(ns monkey.ci.gui.admin.credits.db
  (:require [monkey.ci.gui.loader :as lo]))

(def cust-by-name ::cust-by-name)
(def cust-by-id ::cust-by-id)

(defn get-customers-by-name [db]
  (lo/get-value db cust-by-name))

(defn get-customers-by-id [db]
  (lo/get-value db cust-by-id))

(defn get-customers [db]
  (concat (get-customers-by-name db)
          (get-customers-by-id db)))

(defn reset-customers [db]
  (-> db
      (lo/set-value cust-by-name [])
      (lo/set-value cust-by-id [])))

(defn customers-loading? [db]
  (or (lo/loading? db cust-by-name)
      (lo/loading? db cust-by-id)))

(defn customers-loaded? [db]
  (or (lo/loaded? db cust-by-name)
      (lo/loaded? db cust-by-id)))
