(ns monkey.ci.storage.sql.repo
  (:require [clojure.spec.alpha :as spec]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci.entities
             [core :as ec]
             [repo :as er]]
            [monkey.ci.labels :as lbl]
            [monkey.ci.spec
             [db-entities]
             [entities]]
            [monkey.ci.storage.sql.common :as sc]))

(defn repo->db
  "Converts the repository into an entity that can be sent to the database."
  [r org-id]
  (-> r
      (select-keys [:name :url :main-branch :github-id :public])
      (dissoc :id)
      (assoc :display-id (:id r)
             :org-id org-id)))

(defn db->repo
  "Converts the repo entity (a db record) into a repository.  If `f` is provided,
   it is invoked to allow some processing on the resulting object."
  [re & [f]]
  (cond->
      (-> re
          (dissoc :cuid :org-id :display-id)
          (assoc :id (:display-id re))
          (mc/update-existing :labels sc/db->labels)
          (sc/drop-nil))
    f (f re)))

(defn- insert-repo-labels [conn labels re]
  (when-not (empty? labels)
    (->> labels
         (map #(assoc % :repo-id (:id re)))
         (ec/insert-repo-labels conn))))

(defn- update-repo-labels [conn labels]
  (doseq [l labels]
    (ec/update-repo-label conn l)))

(defn- delete-repo-labels [conn labels]
  (when-not (empty? labels)
    (ec/delete-repo-labels conn [:in :id (map :id labels)])))

(defn- sync-repo-labels [conn labels re]
  {:pre [(some? (:id re))]}
  (let [ex (ec/select-repo-labels conn (ec/by-repo (:id re)))
        {:keys [insert update delete] :as r} (lbl/reconcile-labels ex labels)]
    (log/debug "Reconciled labels" labels "into" r)
    (insert-repo-labels conn insert re)
    (update-repo-labels conn update)
    (delete-repo-labels conn delete)))

(defn- insert-repo [conn re repo]
  (let [re (ec/insert-repo conn re)]
    (insert-repo-labels conn (:labels repo) re)
    ;; Also create an initial repo idx record for build index calculation
    (ec/insert-repo-idx conn {:repo-id (:id re)
                              :next-idx 1})))

(defn- update-repo [conn re repo existing]
  (when (not= re existing)
    (let [re (merge existing re)]
      (ec/update-repo conn re)
      (sync-repo-labels conn (:labels repo) re))))

(defn select-repo-id-by-sid [conn [org-id repo-id]]
  (er/repo-for-build-sid conn org-id repo-id))

(defn upsert-repo [conn repo org-id]
  (spec/valid? :entity/repo repo)
  (let [re (repo->db repo org-id)]
    (spec/valid? :db/repo re)
    (if-let [existing (ec/select-repo conn [:and
                                            (ec/by-org org-id)
                                            (ec/by-display-id (:id repo))])]
      (update-repo conn re repo existing)
      (insert-repo conn re repo))))

(defn upsert-repos [conn {:keys [repos]} org-id]
  (doseq [[_ r] repos]
    (upsert-repo conn r org-id)))

(defn delete-repo [st sid]
  (let [conn (sc/get-conn st)]
    (when-let [repo-id (select-repo-id-by-sid conn sid)]
      ;; Other records are deleted by cascading
      (pos? (ec/delete-repos conn (ec/by-id repo-id))))))

(defn count-repos [st]
  (ec/count-entities (sc/get-conn st) :repos))

(defn select-repo-display-ids [st org-id]
  (er/repo-display-ids (sc/get-conn st) org-id))
