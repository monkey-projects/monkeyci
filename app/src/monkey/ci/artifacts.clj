(ns monkey.ci.artifacts
  "Functionality for saving/restoring artifacts.  This is similar to caches, but
   where caches are used between the same jobs in different builds, artifacts are
   used for different jobs in the same build.  Artifacts can also be exposed to
   the outside world."
  (:require [babashka.fs :as fs]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as blob]
             [build :as b]
             [config :as config]
             [oci :as oci]
             [runtime :as rt]]))

(defn- get-store [rt k]
  (get rt k))

(defn artifact-archive-path [{:keys [build]} id]
  ;; The blob archive path is the build sid with the blob id added.
  (str (cs/join "/" (concat (:sid build) [id])) ".tgz"))

(defn- job-blobs [rt {:keys [store-key job-key]}]
  (let [c (get-in rt [:job job-key])]
    (when (get-store rt store-key)
      c)))

(defn- do-with-blobs [rt conf f]
  (->> (job-blobs rt conf)
       (map (partial f rt))
       (apply md/zip)))

(defn- mb [f]
  (if (fs/exists? f)
    (float (/ (fs/size f) (* 1024 1024)))
    0.0))

(defn save-blob
  "Saves a single blob path"
  [{:keys [build-path store-key]} rt {:keys [path id]}]
  (let [fullp (b/job-relative-dir rt path)]
    (log/debug "Saving blob:" id "at path" path "(full path:" fullp ")")
    (md/chain
     (blob/save (get-store rt store-key)
                fullp
                (build-path rt id))
     (fn [{:keys [dest entries] :as r}]
       (log/debugf "Zipped %d entries to %s (%.2f MB)" (count entries) dest (mb dest))
       r))))

(defn save-generic [rt conf]
  (md/chain
   (do-with-blobs rt conf (partial save-blob conf))
   (partial remove nil?)))

(defn save-artifacts
  "Saves all artifacts according to the job configuration."
  [rt]
  (save-generic rt
                {:job-key :save-artifacts
                 :store-key :artifacts
                 :build-path artifact-archive-path}))

(defn restore-blob [{:keys [store-key build-path]} rt {:keys [id path]}]
  (log/debug "Restoring blob:" id "to path" path)
  (md/chain
   (blob/restore (get-store rt store-key)
                 (build-path rt id)
                 ;; Restore to the parent path because the dir name will be in the archive
                 (-> (b/job-relative-dir rt path)
                     (fs/parent)
                     (fs/canonicalize)
                     (str)))
   (fn [{:keys [entries src dest] :as r}]
     (log/debugf "Unzipped %d entries from %s (%.2f MB) to %s" (count entries) src (mb src) dest)
     r)))

(defn restore-generic [rt conf]
  (do-with-blobs rt conf (partial restore-blob conf)))

(defn restore-artifacts
  [rt]
  (restore-generic rt
                   {:job-key :restore-artifacts
                    :store-key :artifacts
                    :build-path artifact-archive-path}))

(defn wrap-artifacts [f]
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

;;; Config handling

(defmethod config/normalize-key :artifacts [k conf]
  (config/normalize-typed k conf (partial blob/normalize-blob-config k)))

(defmethod rt/setup-runtime :artifacts [conf k]
  (when (k conf)
    (blob/make-blob-store conf k)))
