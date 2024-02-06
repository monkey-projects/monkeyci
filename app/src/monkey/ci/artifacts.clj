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
             [config :as config]
             [context :as c]
             [oci :as oci]]))

(defn- get-store [ctx k]
  (get-in ctx [k :store]))

(defn artifact-archive-path [{:keys [build]} id]
  ;; The artifact archive path is the build sid with the artifact id added.
  (str (cs/join "/" (concat (:sid build) [id])) ".tgz"))

(defn- step-artifacts [ctx {:keys [store-key step-key]}]
  (let [c (get-in ctx [:step step-key])]
    (when (get-store ctx store-key)
      c)))

(defn- do-with-artifacts [ctx conf f]
  (->> (step-artifacts ctx conf)
       (map (partial f ctx))
       (apply md/zip)))

(defn save-artifact
  "Saves a single artifact path"
  [{:keys [build-path store-key]} ctx {:keys [path id]}]
  (log/debug "Saving artifact:" id "at path" path)
  (blob/save (get-store ctx store-key)
             (c/step-relative-dir ctx path)
             (build-path ctx id)))

(defn save-generic [ctx conf]
  (do-with-artifacts ctx conf (partial save-artifact conf)))

(defn save-artifacts
  "Saves all artifacts according to the step configuration."
  [ctx]
  (save-generic ctx
                {:step-key :save-artifacts
                 :store-key :artifacts
                 :build-path artifact-archive-path}))

(defn restore-artifact [{:keys [store-key build-path]} ctx {:keys [id path]}]
  (log/debug "Restoring artifact:" id "to path" path)
  (blob/restore (get-store ctx store-key)
                (build-path ctx id)
                ;; Restore to the parent path because the dir name will be in the archive
                (-> (c/step-relative-dir ctx path)
                    (fs/parent)
                    (fs/canonicalize)
                    (str))))

(defn restore-generic [ctx conf]
  (do-with-artifacts ctx conf (partial restore-artifact conf)))

(defn restore-artifacts
  [ctx]
  (restore-generic ctx
                   {:step-key :restore-artifacts
                    :store-key :artifacts
                    :build-path artifact-archive-path}))

(defn wrap-artifacts [f]
  (fn [ctx]
    @(md/chain
      (restore-artifacts ctx)
      (fn [c]
        (assoc-in ctx [:step :restored-artifacts] c))
      f
      (fn [r]
        (save-artifacts ctx)
        r))))

(defmethod config/normalize-key :artifacts [_ conf]
  (oci/group-credentials conf))
