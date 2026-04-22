(ns monkey.ci.artifacts
  "Functionality for saving/restoring artifacts.  This is similar to caches, but
   where caches are used between the same jobs in different builds, artifacts are
   used for different jobs in the same build.  Artifacts can also be exposed to
   the outside world."
  (:require [babashka.fs :as fs]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [blob :as blob]
             [build :as b]
             [protocols :as p]
             [utils :as u]]))

(defn- do-with-blobs
  "Fetches blobs configurations from the job using the job key, then
   applies `f` to each of them.  This is only executed if a blob store 
   is configured."
  [{:keys [job repo job-key]} f]
  (md/chain
   (->> (when repo
          (job-key job))
        (map f)
        (apply md/zip))
   (partial remove nil?)))

(defn- save-blob
  "Saves a single blob path, using the blob configuration and artifact info."
  [{:keys [job sid checkout-dir repo]} {:keys [path id]}]
  ;; TODO Make paths relative to checkout dir because using job work dir can be wrong
  ;; when the work dir of the saving job is not the same as the restoring job.
  (let [fullp (b/job-relative-dir job checkout-dir path)]
    (log/debug "Saving blob:" id "at path" path "(full path:" fullp ")")
    (md/chain
     (p/save-artifact repo sid id fullp)
     (fn [{:keys [dest entries] :as r}]
       (if (not-empty entries)
         (log/debugf "Zipped %d entries to %s (%.2f MB)" (count entries) dest (u/file-size-mb dest))
         (log/warn "No files to archive for" id "at" path))
       r))))

(defn save-generic [conf]
  (do-with-blobs conf (partial save-blob conf)))

(defn restore-blob [{:keys [repo job sid checkout-dir]} {:keys [id path]}]
  (log/debug "Restoring blob:" id "to path" path)
  (md/chain
   (p/restore-artifact repo
                       sid
                       id
                       (-> (b/job-relative-dir job checkout-dir path)
                           (fs/canonicalize)
                           (str)))
   (fn [{:keys [entries src dest] :as r}]
     (let [r (-> r
                 (mc/update-existing :src (comp str fs/canonicalize))
                 (mc/update-existing :dest (comp str fs/canonicalize))
                 (mc/update-existing :entries count))]
       (when r
         (log/debugf "Unzipped %d entries from %s (%.2f MB) to %s" (count entries) src (u/file-size-mb src) dest))
       r))))

(defn restore-generic [conf]
  (do-with-blobs conf (partial restore-blob conf)))

;;; Protocol implementations

(defrecord BlobArtifactRepository [store path-fn]
  p/ArtifactRepository
  (restore-artifact [this sid id dest]
    (blob/restore store (path-fn sid id) dest))

  (save-artifact [this sid id src]
    (blob/save store src (path-fn sid id))))

;;; Artifact specific functions

(defn build-sid->artifact-path
  "Returns the path to the artifact with specified id for given build sid"
  [sid id]
  (str (cs/join "/" (concat sid [id])) blob/extension))

(defn rt->config
  "Creates a configuration object for artifacts from the runtime"
  [rt]
  (-> (select-keys rt [:job :sid])
      (assoc :repo (:artifacts rt)
             ;; Need to support both build or checkout dir, depending on context
             :checkout-dir (or (:checkout-dir rt)
                               (b/checkout-dir (:build rt))))))

(defn save-artifacts
  "Saves all artifacts according to the job configuration."
  [rt]
  (log/debug "Saving artifacts for job" (:job rt))
  (-> (rt->config rt)
      (assoc :job-key :save-artifacts)
      (save-generic)))

(defn restore-artifacts
  [rt]
  (-> (rt->config rt)
      (assoc :job-key :restore-artifacts)
      (restore-generic)))

(defn wrap-artifacts
  "Wraps `f`, which is a 1-arity function that takes a runtime configuration,
   so that artifacts are restored of the job found in the runtime.  After the 
   invocation of `f`, saves any published artifacts.  Returns a deferred with
   the updated runtime."
  [f]
  (fn [rt]
    (md/chain
     (restore-artifacts rt)
     (fn [c]
       (assoc-in rt [:job :restored-artifacts] c))
     f
     (fn [r]
       (md/chain
        (save-artifacts rt)
        (constantly r))))))

(defn make-blob-repository
  "Creates an artifact repository that uses a blob repo as it's target.  If an sid is
   specified, it is used instead of the one passed in by the artifact repo."
  ([store sid]
   (->BlobArtifactRepository store (fn [_ id] (build-sid->artifact-path sid id))))
  ([store]
   (->BlobArtifactRepository store build-sid->artifact-path)))

;;; Interceptors

(def get-restored ::restored)

(defn set-restored [ctx r]
  (assoc ctx ::restored r))

(defn restore-interceptor
  "Interceptor that restores artifacts using the given repo, build sid and job retrieved
   from the context using `get-job-ctx`.  Adds details about the restored artifacts to
   the context."
  [get-job-ctx]
  {:name ::restore-artifacts
   :enter (fn [ctx]
            (->> (get-job-ctx ctx)
                 (restore-artifacts)
                 ;; Note that this will block the event processing while the operation continues
                 ;; so we may consider switching to async handling.
                 (deref)
                 (set-restored ctx)))})

(def get-saved ::saved)

(defn set-saved [ctx r]
  (assoc ctx ::saved r))

(defn save-interceptor
  "Interceptor that saves artifacts using the repo, build and job retrieved
   from the context using `get-job-ctx`.  Adds details about the saved artifacts to
   the context."
  [get-job-ctx]
  {:name ::save-artifacts
   :enter (fn [ctx]
            (->> (get-job-ctx ctx)
                 (save-artifacts)
                 ;; Note that this will block the event processing while the operation continues
                 ;; so we may consider switching to async handling.
                 (deref)
                 (set-saved ctx)))})
