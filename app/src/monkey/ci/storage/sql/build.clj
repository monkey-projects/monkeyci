(ns monkey.ci.storage.sql.build
  (:require [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci.entities
             [build :as eb]
             [core :as ec]
             [repo :as er]]
            [monkey.ci.storage.sql
             [common :as sc]
             [job :as sj]]))

(defn- build->db [build]
  (-> build
      (select-keys [:status :start-time :end-time :idx :git :credits :source :message])
      ;; Drop some sensitive information
      (mc/update-existing :git dissoc :ssh-keys-dir)
      (mc/update-existing-in [:git :ssh-keys] (partial map #(select-keys % [:id :description])))
      (assoc :display-id (:build-id build)
             :script-dir (get-in build [:script :script-dir]))))

(defn- db->build [build]
  (-> build
      (select-keys [:status :start-time :end-time :idx :git :credits :source :message])
      (ec/start-time->int)
      (ec/end-time->int)
      (assoc :build-id (:display-id build)
             :script (select-keys build [:script-dir]))
      (update :credits (fnil int 0))
      (mc/assoc-some :org-id (:org-cuid build)
                     :repo-id (:repo-display-id build))
      (sc/drop-nil)))

(defn- insert-build [conn build]
  (when-let [repo-id (er/repo-for-build-sid conn (:org-id build) (:repo-id build))]
    (let [{:keys [id] :as ins} (ec/insert-build conn (-> (build->db build)
                                                         (assoc :repo-id repo-id)))]
      ins)))

(defn- update-build [conn build existing]
  (ec/update-build conn (merge existing (build->db build)))  
  build)

(defn upsert-build [conn build]
  ;; Fetch build by org cuild and repo and build display ids
  (if-let [existing (eb/select-build-by-sid conn (:org-id build) (:repo-id build) (:build-id build))]
    (update-build conn build existing)
    (insert-build conn build)))

(defn- hydrate-build
  "Fetches jobs related to the build"
  [conn [org-id repo-id] build]
  (log/debug "Hydrating build:" build)
  (let [jobs (sj/select-build-jobs conn (:id build))]
    (log/debug "Jobs for" (:id build) ":" jobs)
    (cond-> (-> (db->build build)
                (assoc :org-id org-id
                       :repo-id repo-id)
                (update :script sc/drop-nil)
                (sc/drop-nil))
      (not-empty jobs) (assoc-in [:script :jobs] jobs))))

(defn select-build [conn [org-id repo-id :as sid]]
  (when-let [build (apply eb/select-build-by-sid conn sid)]
    (hydrate-build conn sid build)))

(defn select-repo-builds
  "Retrieves all builds and their details for given repository"
  [st [org-id repo-id]]
  (letfn [(add-ids [b]
            (assoc b
                   :org-id org-id
                   :repo-id repo-id))]
    ;; Fetch all build details, don't include jobs since we don't need them at this point
    ;; and they can become a very large dataset.
    (->> (eb/select-builds-for-repo (sc/get-conn st) org-id repo-id)
         (map db->build)
         (map add-ids))))

(defn build-exists? [conn sid]
  (some? (apply eb/select-build-by-sid conn sid)))

(defn select-repo-build-ids [conn sid]
  (apply eb/select-build-ids-for-repo conn sid))

(defn select-org-builds-since [st org-id ts]
  (->> (eb/select-builds-for-org-since (sc/get-conn st) org-id ts)
       (map db->build)))

(defn select-latest-build [st [org-id repo-id :as sid]]
  (let [conn (sc/get-conn st)]
    (some->> (eb/select-latest-build conn org-id repo-id)
             (hydrate-build conn sid))))

(defn select-latest-org-builds [st org-id]
  (->> (eb/select-latest-builds (sc/get-conn st) org-id)
       (map db->build)))

(defn select-latest-n-org-builds [st org-id n]
  (->> (eb/select-latest-n-builds (sc/get-conn st) org-id n)
       (map db->build)))

(defn select-next-build-idx [st [org-id repo-id]]
  (er/next-repo-idx (sc/get-conn st) org-id repo-id))
