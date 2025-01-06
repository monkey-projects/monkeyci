(ns monkey.ci.gui.admin.credits.db
  (:require [monkey.ci.gui.loader :as lo]))

(def cust-by-name ::cust-by-name)
(def cust-by-id ::cust-by-id)
(def credits ::credits)

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

(defn get-credits [db]
  (lo/get-value db credits))

(defn update-credits [db f & args]
  (apply lo/update-value db credits f args))

(defn get-credit-alerts [db]
  (lo/get-alerts db credits))

(defn set-credit-alerts [db a]
  (lo/set-alerts db credits a))

(defn reset-credit-alerts [db]
  (lo/reset-alerts db credits))

(defn credits-loading? [db]
  (lo/loading? db credits))

(def saving? ::saving?)

(defn set-saving [db]
  (assoc db saving? true))

(defn reset-saving [db]
  (dissoc db saving?))
