(ns monkey.ci.cache
  "Functionality for saving/restoring caches.  This uses blobs."
  (:require [babashka.fs :as fs]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [artifacts :as art]
             [blob :as blob]
             [build :as b]
             [config :as config]
             [oci :as oci]
             [runtime :as rt]]))

(defn cache-archive-path [build id]
  ;; The cache archive path is the repo sid with the cache id added.
  ;; Build id is not used since caches are meant to supersede builds.
  (str (cs/join "/" (concat (butlast (b/sid build)) [id])) ".tgz"))

(defn- rt->config [rt]
  (-> (select-keys rt [:job :build])
      (assoc :store (:cache rt)
             :job-key :caches
             :build-path (partial cache-archive-path (:build rt)))))

(defn save-caches
  "If the job configured in the context uses caching, saves it according
   to the cache configurations."
  [rt]
  (art/save-generic (rt->config rt)))

(defn restore-caches
  [rt]
  (art/restore-generic (rt->config rt)))

(defn wrap-caches
  "Wraps fn `f` so that caches are restored/saved as configured on the job."
  [f]
  (fn [rt]
    (md/chain
     (restore-caches rt)
     (fn [c]
       (assoc-in rt [:job :caches] c))
     f
     (fn [r]
       (md/chain
        (save-caches rt)
        (constantly r))))))

;;; Config handling

(defmethod config/normalize-key :cache [k conf]
  (config/normalize-typed k conf (partial blob/normalize-blob-config k)))

(defmethod rt/setup-runtime :cache [conf k]
  (when (k conf)
    (blob/make-blob-store conf k)))
