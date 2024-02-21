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
  ;; The artifact archive path is the build sid with the artifact id added.
  (str (cs/join "/" (concat (:sid build) [id])) ".tgz"))

(defn- step-artifacts [rt {:keys [store-key step-key]}]
  (let [c (get-in rt [:step step-key])]
    (when (get-store rt store-key)
      c)))

(defn- do-with-artifacts [rt conf f]
  (->> (step-artifacts rt conf)
       (map (partial f rt))
       (apply md/zip)))

(defn save-artifact
  "Saves a single artifact path"
  [{:keys [build-path store-key]} rt {:keys [path id]}]
  (log/debug "Saving artifact:" id "at path" path)
  (blob/save (get-store rt store-key)
             (b/step-relative-dir rt path)
             (build-path rt id)))

(defn save-generic [rt conf]
  (do-with-artifacts rt conf (partial save-artifact conf)))

(defn save-artifacts
  "Saves all artifacts according to the step configuration."
  [rt]
  (save-generic rt
                {:step-key :save-artifacts
                 :store-key :artifacts
                 :build-path artifact-archive-path}))

(defn restore-artifact [{:keys [store-key build-path]} rt {:keys [id path]}]
  (log/debug "Restoring artifact:" id "to path" path)
  (blob/restore (get-store rt store-key)
                (build-path rt id)
                ;; Restore to the parent path because the dir name will be in the archive
                (-> (b/step-relative-dir rt path)
                    (fs/parent)
                    (fs/canonicalize)
                    (str))))

(defn restore-generic [rt conf]
  (do-with-artifacts rt conf (partial restore-artifact conf)))

(defn restore-artifacts
  [rt]
  (restore-generic rt
                   {:step-key :restore-artifacts
                    :store-key :artifacts
                    :build-path artifact-archive-path}))

(defn wrap-artifacts [f]
  (fn [rt]
    @(md/chain
      (restore-artifacts rt)
      (fn [c]
        (assoc-in rt [:step :restored-artifacts] c))
      f
      (fn [r]
        (save-artifacts rt)
        r))))

;;; Config handling

(defmethod config/normalize-key :artifacts [k conf]
  (config/normalize-typed k conf (partial blob/normalize-blob-config k)))

(defmethod rt/setup-runtime :artifacts [conf k]
  (when (k conf)
    (blob/make-blob-store conf k)))
