(ns monkey.ci.gui.org.db
  (:require [monkey.ci.gui.loader :as lo]))

(def org ::org)

(defn set-org [db i]
  (lo/set-value db org i))

(defn get-org [db]
  (lo/get-value db org))

(defn get-alerts [db]
  (lo/get-alerts db org))

(defn set-alerts [db a]
  (lo/set-alerts db org a))

(defn update-org [db f & args]
  (apply lo/update-value db org f args))

(defn replace-repo
  "Updates org repos by replacing the existing repo with the same id."
  [db updated-repo]
  (letfn [(replace-with [repos]
            (if-let [match (->> repos
                                (filter (comp (partial = (:id updated-repo)) :id))
                                (first))]
              (replace {match updated-repo} repos)
              (conj repos updated-repo)))]
    (update-org db update :repos replace-with)))

(def repo-alerts ::repo-alerts)

(defn set-repo-alerts [db a]
  (assoc db repo-alerts a))

(defn reset-repo-alerts [db]
  (dissoc db repo-alerts))

(def org-creating? ::org-creating)

(defn mark-org-creating [db]
  (assoc db org-creating? true))

(defn unmark-org-creating [db]
  (dissoc db org-creating?))

(def create-alerts ::create-alerts)

(defn set-create-alerts [db a]
  (assoc db create-alerts a))

(defn reset-create-alerts [db]
  (dissoc db create-alerts))

(def edit-alerts ::edit-alerts)

(defn set-edit-alerts [db a]
  (assoc db edit-alerts a))

(defn reset-edit-alerts [db]
  (dissoc db edit-alerts))

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
