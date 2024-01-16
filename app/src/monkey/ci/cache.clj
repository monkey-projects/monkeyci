(ns monkey.ci.cache
  "Functionality for saving/restoring caches.  This uses blobs."
  (:require [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as blob]
             [context :as c]]))

(def cache-store (comp :store :cache))

(defn cache-archive-path [{:keys [build]} id]
  ;; The cache archive path is the repo sid with the cache id added.
  ;; Build id is not used since caches are meant to supersede builds.
  (str (cs/join "/" (concat (butlast (:sid build)) [id])) ".tgz"))

(defn- step-caches [ctx]
  (let [c (get-in ctx [:step :caches])]
    (when (cache-store ctx) c)))

(defn- do-with-caches [ctx f]
  (->> (step-caches ctx)
       (map (partial f ctx))
       (apply md/zip)))

(defn save-cache
  "Saves a single cache path"
  [ctx {:keys [path id]}]
  ;; TODO Add a way to check whether we actually need to update the cache or not
  (log/debug "Saving cache:" id "at path" path)
  (blob/save (cache-store ctx)
             (c/step-relative-dir ctx path)
             (cache-archive-path ctx id)))

(defn save-caches
  "If the step configured in the context uses caching, saves it according
   to the cache configurations."
  [ctx]
  (do-with-caches ctx save-cache))

(defn restore-cache [ctx {:keys [id path]}]
  (log/debug "Restoring cache:" id "to path" path)
  (blob/restore (cache-store ctx)
                (cache-archive-path ctx id)
                (c/step-relative-dir ctx path)))

(defn restore-caches
  [ctx]
  (do-with-caches ctx restore-cache))
