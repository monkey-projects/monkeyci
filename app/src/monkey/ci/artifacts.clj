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
             [runtime :as rt]]
            [monkey.ci.build
             [api :as api]
             [archive :as archive]]))

(defn- do-with-blobs
  "Fetches blobs configurations from the job using the job key, then
   applies `f` to each of them.  This is only executed if a blob store 
   is configured."
  [{:keys [job store job-key]} f]
  (md/chain
   (->> (when store
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
  [{:keys [job build store build-path]} {:keys [path id]}]
  ;; TODO Make paths relative to checkout dir because using job work dir can be wroing
  ;; when the work dir of the saving job is not the same as the restoring job.
  (let [fullp (b/job-relative-dir job build path)]
    (log/debug "Saving blob:" id "at path" path "(full path:" fullp ")")
    (md/chain
     (blob/save store
                fullp
                (build-path id))
     (fn [{:keys [dest entries] :as r}]
       (if (not-empty entries)
         (log/debugf "Zipped %d entries to %s (%.2f MB)" (count entries) dest (mb dest))
         (log/warn "No files to archive for" id "at" path))
       r))))

(defn save-generic [conf]
  (do-with-blobs conf (partial save-blob conf)))

(defn restore-blob [{:keys [store build-path job build]} {:keys [id path]}]
  (log/debug "Restoring blob:" id "to path" path)
  (md/chain
   (blob/restore store
                 (build-path id)
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
  [{:keys [build] :as rt}]
  (-> (select-keys rt [:job :build])
      (assoc :store (rt/artifacts rt)
             :build-path (partial artifact-archive-path build))))

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

(defprotocol ArtifactRepository
  (download-artifact [this id dest]
    "Downloads artifact with given id to the specified destination.  Returns the
     destination.  If `dest` is `:stream`, returns it as an input stream.")
  (upload-artifact [this id src]
    "Uploads the artifact with given id from `src`, which can be a file or an
     input stream."))

(defrecord BlobArtifactRepository [store build]
  ArtifactRepository
  (download-artifact [this id dest]
    (blob/restore store (artifact-archive-path build id) dest))

  (upload-artifact [this id src]
    (blob/save store src (artifact-archive-path build id))))

(def make-blob-repository ->BlobArtifactRepository)

(defrecord BuildApiArtifactRepository [client base-path]
  ArtifactRepository
  (download-artifact [this id dest]
    (md/chain
     (client {:method :get
              :path (str base-path id)
              :as :stream})
     :body
     #(archive/extract % dest)))

  (upload-artifact [this id src]
    (let [tmp (fs/create-temp-file)
          arch (blob/make-archive src (fs/file tmp))
          stream (io/input-stream (fs/file tmp))]
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
              (fs/delete tmp)))))))

(defn make-build-api-repository [client]
  (->BuildApiArtifactRepository client "/artifact/"))

;;; Config handling

(defmethod config/normalize-key :artifacts [k conf]
  (config/normalize-typed k conf (partial blob/normalize-blob-config k)))

(defmethod rt/setup-runtime :artifacts [conf k]
  (when (k conf)
    (blob/make-blob-store conf k)))
