(ns monkey.ci.cache
  "Functionality for saving/restoring caches.  This uses blobs."
  (:require [babashka.fs :as fs]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [artifacts :as art]
             [blob :as blob]
             [config :as config]
             [context :as c]
             [oci :as oci]
             [runtime :as rt]]))

(defn cache-archive-path [{:keys [build]} id]
  ;; The cache archive path is the repo sid with the cache id added.
  ;; Build id is not used since caches are meant to supersede builds.
  (str (cs/join "/" (concat (butlast (:sid build)) [id])) ".tgz"))

(def cache-config {:store-key :cache
                   :step-key :caches
                   :build-path cache-archive-path})

(defn save-caches
  "If the step configured in the context uses caching, saves it according
   to the cache configurations."
  [ctx]
  (art/save-generic ctx cache-config))

(defn restore-caches
  [ctx]
  (art/restore-generic ctx cache-config))

(defn wrap-caches
  "Wraps fn `f` so that caches are restored/saved as configured on the step."
  [f]
  (fn [ctx]
    @(md/chain
      (restore-caches ctx)
      (fn [c]
        (assoc-in ctx [:step :caches] c))
      f
      (fn [r]
        (save-caches ctx)
        r))))

;;; Config handling

(defmethod config/normalize-key :cache [k conf]
  (config/normalize-typed k conf (partial blob/normalize-blob-config k)))

(defmethod rt/setup-runtime :cache [conf _]
  (blob/make-blob-store conf :cache))
