(ns monkey.ci.artifacts
  "Functionality for saving/restoring artifacts.  This is similar to caches, but
   where caches are used between the same jobs in different builds, artifacts are
   used for different jobs in the same build.  Artifacts can also be exposed to
   the outside world."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [blob :as blob]
             [build :as b]
             [config :as config]
             [oci :as oci]
             [runtime :as rt]
             [utils :as u]]
            [monkey.ci.build
             [api :as api]
             [archive :as archive]]))

(defprotocol ArtifactRepository
  (restore-artifact [this id dest]
    "Downloads and extracts artifact with given id to the specified destination 
     directory.  Returns the destination.")
  (save-artifact [this id src]
    "Creates an archive and uploads the artifact with given id from `src`, which 
     can be a directory or file."))

(defn repo? [x]
  (satisfies? ArtifactRepository x))

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

(defn- mb
  "Returns file size in MB"
  [f]
  (if (fs/exists? f)
    (float (/ (fs/size f) (* 1024 1024)))
    0.0))

(defn- save-blob
  "Saves a single blob path, using the blob configuration and artifact info."
  [{:keys [job build repo]} {:keys [path id]}]
  ;; TODO Make paths relative to checkout dir because using job work dir can be wrong
  ;; when the work dir of the saving job is not the same as the restoring job.
  (let [fullp (b/job-relative-dir job build path)]
    (log/debug "Saving blob:" id "at path" path "(full path:" fullp ")")
    (md/chain
     (save-artifact repo id fullp)
     (fn [{:keys [dest entries] :as r}]
       (if (not-empty entries)
         (log/debugf "Zipped %d entries to %s (%.2f MB)" (count entries) dest (mb dest))
         (log/warn "No files to archive for" id "at" path))
       r))))

(defn save-generic [conf]
  (do-with-blobs conf (partial save-blob conf)))

(defn restore-blob [{:keys [repo job build]} {:keys [id path]}]
  (log/debug "Restoring blob:" id "to path" path)
  (md/chain
   (restore-artifact repo
                     id
                     ;; Restore to the parent path because the dir name will be in the archive
                     (-> (b/job-relative-dir job build path)
                         (fs/parent)
                         (fs/canonicalize)
                         (str)))
   (fn [{:keys [entries src dest] :as r}]
     (let [r (-> r
                 (mc/update-existing :src (comp str fs/canonicalize))
                 (mc/update-existing :dest (comp str fs/canonicalize))
                 (mc/update-existing :entries count))]
       (when r
         (log/debugf "Unzipped %d entries from %s (%.2f MB) to %s" (count entries) src (mb src) dest))
       r))))

(defn restore-generic [conf]
  (do-with-blobs conf (partial restore-blob conf)))

;;; Protocol implementations

(defrecord BlobArtifactRepository [store path-fn]
  ArtifactRepository
  (restore-artifact [this id dest]
    (blob/restore store (path-fn id) dest))

  (save-artifact [this id src]
    (blob/save store src (path-fn id))))

(defrecord BuildApiArtifactRepository [client base-path]
  ArtifactRepository
  (restore-artifact [this id dest]
    (log/debug "Restoring artifact using build API:" id "to" dest)
    (u/log-deferred-elapsed
     (-> (client {:method :get
                  :path (str base-path id)
                  :as :stream})
         (md/chain
          :body
          #(archive/extract % dest))
         (md/catch (fn [ex]
                     (if (= 404 (:status (ex-data ex)))
                       (log/warn "Artifact not found:" id)
                       (throw ex)))))
     (str "Restored artifact from build API: " id)))

  (save-artifact [this id src]
    (let [tmp (fs/create-temp-file)
          ;; TODO Skip the tmp file intermediate step, it takes up disk space and is slower
          arch (blob/make-archive src (fs/file tmp))
          stream (io/input-stream (fs/file tmp))]
      (log/debugf "Uploading artifact/cache to api server: %s from %s (compressed size: %.2f MB)" id src (mb arch))
      (u/log-deferred-elapsed
       (-> (client (api/as-edn {:method :put
                                :path (str base-path id)
                                :body stream}))
           (md/chain
            :body
            (partial merge arch))
           (md/finally
             ;; Clean up
             (fn []
               (.close stream)
               (fs/delete tmp))))
       (str "Saved artifact to build API: " id)))))

;;; Artifact specific functions

(defn build-sid->artifact-path
  "Returns the path to the artifact with specified id for given build sid"
  [sid id]
  (str (cs/join "/" (concat sid [id])) blob/extension))

(defn artifact-archive-path
  "Returns path for the archive in the given build"
  [build id]
  ;; The blob archive path is the build sid with the blob id added.
  (build-sid->artifact-path (b/sid build) id))

(defn- rt->config
  "Creates a configuration object for artifacts from the runtime"
  [rt]
  (-> (select-keys rt [:job :build])
      (assoc :repo (:artifacts rt))))

(defn save-artifacts
  "Saves all artifacts according to the job configuration."
  [rt]
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

(defn make-blob-repository [store build]
  (->BlobArtifactRepository store (partial artifact-archive-path build)))

(defn make-build-api-repository [client]
  (->BuildApiArtifactRepository client "/artifact/"))

;;; Config handling

(defmethod config/normalize-key :artifacts [k conf]
  (config/normalize-typed k conf (partial blob/normalize-blob-config k)))

(defmethod rt/setup-runtime :artifacts [conf k]
  (when (k conf)
    (blob/make-blob-store conf k)))
