(ns monkey.ci.gui.repo.db
  (:require [monkey.ci.gui.loader :as lo]))

(def id ::repo)

(defn alerts [db]
  (lo/get-alerts db id))

(defn set-alerts [db a]
  (lo/set-alerts db id a))

(defn reset-alerts [db]
  (lo/reset-alerts db id))

(defn get-builds [db]
  (lo/get-value db id))

(defn set-builds [db b]
  (lo/set-value db id b))

(def build-uid (juxt :org-id :repo-id :build-id))

(defn find-build [db b]
  (->> (get-builds db)
       (filter (comp (partial = (build-uid b)) build-uid))
       (first)))

(defn update-build
  "Replaces build in the list, or adds it if not present yet"
  [db b]
  (if-let [m (find-build db b)]
    (lo/update-value db id (partial replace {m b}))
    (lo/update-value db id conj b)))

(def latest-build ::latest-build)

(defn set-latest-build [db b]
  (assoc db latest-build b))

(def show-trigger-form? ::show-trigger-form?)

(defn set-show-trigger-form [db f]
  (assoc db show-trigger-form? f))

(def triggering? ::triggering?)

(defn set-triggering [db]
  (assoc db triggering? true))

(defn unset-triggering [db]
  (dissoc db triggering?))

(def editing ::editing)

(defn set-editing [db e]
  (assoc db editing e))

(defn update-editing [db f & args]
  (apply update db editing f args))

(defn update-labels [db f & args]
  (apply update-in db [editing :labels] f args))

(defn update-label [db lbl f & args]
  (update-labels db (partial replace {lbl (apply f lbl args)})))

(def edit-alerts ::edit-alerts)

(defn set-edit-alerts [db a]
  (assoc db edit-alerts a))

(defn reset-edit-alerts [db]
  (dissoc db edit-alerts))

(def saving? ::saving?)

(defn set-saving [db s]
  (assoc db saving? s))

(defn mark-saving [db]
  (set-saving db true))

(defn unmark-saving [db]
  (dissoc db saving?))

(def deleting? ::deleting?)

(defn set-deleting [db s]
  (assoc db deleting? s))

(defn mark-deleting [db]
  (set-deleting db true))

(defn unmark-deleting [db]
  (dissoc db deleting?))

