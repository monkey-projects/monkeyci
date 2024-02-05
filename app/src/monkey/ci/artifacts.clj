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
             [context :as c]]))

(def artifact-store (comp :store :artifacts))

(defn artifact-archive-path [{:keys [build]} id]
  ;; The artifact archive path is the build sid with the artifact id added.
  (str (cs/join "/" (concat (:sid build) [id])) ".tgz"))

(defn- step-artifacts [ctx p]
  (let [c (get-in ctx [:step p])]
    (when (artifact-store ctx) c)))

(defn- do-with-artifacts [ctx p f]
  (->> (step-artifacts ctx p)
       (map (partial f ctx))
       (apply md/zip)))

(defn save-artifact
  "Saves a single artifact path"
  [ctx {:keys [path id]}]
  (log/debug "Saving artifact:" id "at path" path)
  (blob/save (artifact-store ctx)
             (c/step-relative-dir ctx path)
             (artifact-archive-path ctx id)))

(defn save-artifacts
  "Saves all artifacts according to the step configuration."
  [ctx]
  (do-with-artifacts ctx :save-artifacts save-artifact))

(defn restore-artifact [ctx {:keys [id path]}]
  (log/debug "Restoring artifact:" id "to path" path)
  (blob/restore (artifact-store ctx)
                (artifact-archive-path ctx id)
                ;; Restore to the parent path because the dir name will be in the archive
                (-> (c/step-relative-dir ctx path)
                    (fs/parent)
                    (fs/canonicalize)
                    (str))))

(defn restore-artifacts
  [ctx]
  (do-with-artifacts ctx :restore-artifacts restore-artifact))

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

(defn with-apply-artifacts
  "Executes `f`.  If the current step has artifacts configured, restores/saves 
   them as needed."
  [f ctx]
  ((wrap-artifacts f) ctx))
