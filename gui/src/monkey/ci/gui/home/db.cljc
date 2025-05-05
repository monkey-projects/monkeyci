(ns monkey.ci.gui.home.db
  (:require [monkey.ci.gui.loader :as lo]))

(def id ::home)

(defn get-orgs [db]
  (lo/get-value db id))

(defn set-orgs [db c]
  (lo/set-value db id c))

(defn get-alerts [db]
  (lo/get-alerts db id))

(defn set-alerts [db a]
  (lo/set-alerts db id a))

(defn clear-alerts [db]
  (lo/reset-alerts db id))

(def org-searching? ::org-searching?)

(defn set-org-searching [db v]
  (assoc db org-searching? v))

(defn reset-org-searching [db]
  (dissoc db org-searching?))

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

(defn org-joining?
  "Checks if we're in the process of sending a join request to the given org."
  ([db cust-id]
   (some? (when-let [ids (::org-joining db)]
            (ids cust-id))))
  ([db]
   (::org-joining db)))

(defn mark-org-joining [db cust-id]
  (update db ::org-joining (fnil conj #{}) cust-id))

(defn unmark-org-joining [db cust-id]
  (update db ::org-joining disj cust-id))

(defn clear-org-joining [db]
  (dissoc db ::org-joining))
