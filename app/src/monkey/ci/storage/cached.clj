(ns monkey.ci.storage.cached
  "Cached storage implementation.  It wraps another storage and adds caching to it.
   This currently is a very naive implementation.  It should be expanded with event processing,
   in case there are multiple replicas.  Or we should replace it with a 'real' database."
  (:require [clojure.tools.logging :as log]
            [monkey.ci.protocols :as p]))

(deftype CachedStorage [src cache]
  p/Storage
  (write-obj [_ sid obj]
    (when-let [r (p/write-obj src sid obj)]
      (p/write-obj cache sid obj)
      r))

  (read-obj [_ sid]
    (if-let [v (p/read-obj cache sid)]
      v
      (let [v (p/read-obj src sid)]
        (log/debug "Adding to cache:" sid)
        (p/write-obj cache sid v)
        v)))

  (delete-obj [_ sid]
    (p/delete-obj cache sid)
    (p/delete-obj src sid))

  (obj-exists? [_ sid]
    ;; Check the src directly
    (p/obj-exists? src sid))

  (list-obj [_ sid]
    ;; Always list from src
    (p/list-obj src sid)))

