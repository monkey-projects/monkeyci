(ns monkey.ci.gui.home.db
  (:require [monkey.ci.gui.loader :as lo]))

(def id ::home)

(defn get-customers [db]
  (lo/get-value db id))

(defn set-customers [db c]
  (lo/set-value db id c))

(defn get-alerts [db]
  (lo/get-alerts db id))

(defn set-alerts [db a]
  (lo/set-alerts db id a))

(defn clear-alerts [db]
  (lo/reset-alerts db id))

(def customer-searching? ::customer-searching?)

(defn set-customer-searching [db v]
  (assoc db customer-searching? v))

(defn reset-customer-searching [db]
  (dissoc db customer-searching?))

(def join-alerts ::join-alerts)

(defn set-join-alerts [db a]
  (assoc db join-alerts a))

(defn clear-join-alerts [db]
  (dissoc db join-alerts))

(def search-results ::search-results)

(defn set-search-results [db r]
  (assoc db search-results r))

(def join-requests ::join-requests)

(defn set-join-requests [db jr]
  (assoc db join-requests jr))

(defn update-join-requests [db f & args]
  (apply update db join-requests f args))

(defn customer-joining?
  "Checks if we're in the process of sending a join request to the given customer."
  ([db cust-id]
   (some? (when-let [ids (::customer-joining db)]
            (ids cust-id))))
  ([db]
   (::customer-joining db)))

(defn mark-customer-joining [db cust-id]
  (update db ::customer-joining (fnil conj #{}) cust-id))

(defn unmark-customer-joining [db cust-id]
  (update db ::customer-joining disj cust-id))

(defn clear-customer-joining [db]
  (dissoc db ::customer-joining))
