(ns monkey.ci.gui.org.db
  (:require [monkey.ci.gui.loader :as lo]))

(def customer ::customer)

(defn set-customer [db i]
  (lo/set-value db customer i))

(defn get-customer [db]
  (lo/get-value db customer))

(defn get-alerts [db]
  (lo/get-alerts db customer))

(defn set-alerts [db a]
  (lo/set-alerts db customer a))

(defn update-customer [db f & args]
  (apply lo/update-value db customer f args))

(defn replace-repo
  "Updates customer repos by replacing the existing repo with the same id."
  [db updated-repo]
  (letfn [(replace-with [repos]
            (if-let [match (->> repos
                                (filter (comp (partial = (:id updated-repo)) :id))
                                (first))]
              (replace {match updated-repo} repos)
              (conj repos updated-repo)))]
    (update-customer db update :repos replace-with)))

(def repo-alerts ::repo-alerts)

(defn set-repo-alerts [db a]
  (assoc db repo-alerts a))

(defn reset-repo-alerts [db]
  (dissoc db repo-alerts))

(def customer-creating? ::customer-creating)

(defn mark-customer-creating [db]
  (assoc db customer-creating? true))

(defn unmark-customer-creating [db]
  (dissoc db customer-creating?))

(def create-alerts ::create-alerts)

(defn set-create-alerts [db a]
  (assoc db create-alerts a))

(defn reset-create-alerts [db]
  (dissoc db create-alerts))

(def recent-builds ::recent-builds)

(def stats ::stats)
(def credits ::credits)

(defn get-credits [db]
  (lo/get-value db credits))

(def group-by-lbl ::group-by-lbl)

(defn get-group-by-lbl [db]
  (get db group-by-lbl "project"))

(defn set-group-by-lbl [db l]
  (assoc db group-by-lbl l))

(def repo-filter ::repo-filter)

(defn get-repo-filter [db]
  (get db repo-filter))

(defn set-repo-filter [db f]
  (assoc db repo-filter f))

(def ext-repo-filter ::repo-filter)

(defn get-ext-repo-filter [db]
  (get db ext-repo-filter))

(defn set-ext-repo-filter [db f]
  (assoc db ext-repo-filter f))

(def bb-webhooks ::bb-webhooks)

(defn set-bb-webhooks [db wh]
  (assoc db bb-webhooks wh))

(def latest-builds ::latest-builds)
(def get-latest-builds latest-builds)

(defn set-latest-builds [db l]
  (assoc db latest-builds l))
