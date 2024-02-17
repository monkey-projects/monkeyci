(ns monkey.ci.storage.cached
  "Cached storage implementation.  It wraps another storage and adds caching to it.
   This currently is a very naive implementation.  It should be expanded with event processing,
   in case there are multiple replicas.  Or we should replace it with a 'real' database."
  (:require [clojure.tools.logging :as log]
            [monkey.ci.storage :as st]))

(deftype CachedStorage [src cache]
  st/Storage
  (write-obj [_ sid obj]
    (when-let [r (st/write-obj src sid obj)]
      (st/write-obj cache sid obj)
      r))

  (read-obj [_ sid]
    (if-let [v (st/read-obj cache sid)]
      v
      (let [v (st/read-obj src sid)]
        (log/debug "Adding to cache:" sid)
        (st/write-obj cache sid v)
        v)))

  (delete-obj [_ sid]
    (st/delete-obj cache sid)
    (st/delete-obj src sid))

  (obj-exists? [_ sid]
    ;; Check the src directly
    (st/obj-exists? src sid))

  (list-obj [_ sid]
    ;; Always list from src
    (st/list-obj src sid)))

(defn make-cached-storage [src]
  ;; FIXME Set a limit on the cache size
  (->CachedStorage src (st/make-memory-storage)))
