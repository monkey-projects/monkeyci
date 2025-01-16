(ns monkey.ci.gui.admin.credits.db
  (:require [monkey.ci.gui.loader :as lo]))

(def cust-by-name ::cust-by-name)
(def cust-by-id ::cust-by-id)
(def issues ::issues)
(def subscriptions ::subs)

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

(defn get-issues [db]
  (lo/get-value db issues))

(defn update-issues [db f & args]
  (apply lo/update-value db issues f args))

(defn get-issue-alerts [db]
  (lo/get-alerts db issues))

(defn set-issue-alerts [db a]
  (lo/set-alerts db issues a))

(defn reset-issue-alerts [db]
  (lo/reset-alerts db issues))

(defn issues-loading? [db]
  (lo/loading? db issues))

(def issue-saving? ::issue-saving?)

(defn set-issue-saving [db]
  (assoc db issue-saving? true))

(defn reset-issue-saving [db]
  (dissoc db issue-saving?))

(def show-issue-form? ::show-issue-form?)

(defn show-issue-form [db]
  (assoc db show-issue-form? true))

(defn hide-issue-form [db]
  (dissoc db show-issue-form?))

(defn get-subs [db]
  (lo/get-value db subscriptions))

(defn update-subs [db f & args]
  (apply lo/update-value db subscriptions f args))

(defn get-sub-alerts [db]
  (lo/get-alerts db subscriptions))

(defn set-sub-alerts [db a]
  (lo/set-alerts db subscriptions a))

(defn reset-sub-alerts [db]
  (lo/reset-alerts db subscriptions))

(defn subs-loading? [db]
  (lo/loading? db subscriptions))

(def sub-saving? ::sub-saving?)

(defn set-sub-saving [db]
  (assoc db sub-saving? true))

(defn reset-sub-saving [db]
  (dissoc db sub-saving?))

(def show-sub-form? ::show-sub-form?)

(defn show-sub-form [db]
  (assoc db show-sub-form? true))

(defn hide-sub-form [db]
  (dissoc db show-sub-form?))

(def issuing-all? ::issuing-all?)

(defn set-issuing-all [db]
  (assoc db issuing-all? true))

(defn reset-issuing-all [db]
  (dissoc db issuing-all?))

(def issue-all-alerts ::issue-all-alerts)

(defn set-issue-all-alerts [db a]
  (assoc db issue-all-alerts a))

(defn reset-issue-all-alerts [db]
  (dissoc db issue-all-alerts))
