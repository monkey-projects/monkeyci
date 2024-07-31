(ns monkey.ci.gui.customer.db
  (:require [monkey.ci.gui.loader :as lo]))

(def customer ::customer)

(defn set-customer [db i]
  (lo/set-value db customer i))

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

(def github-repos ::github-repos)

(defn set-github-repos [db r]
  (assoc db github-repos r))

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
