(ns monkey.ci.runtime.common
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]))

(defn- maybe-deref [x]
  (cond-> x
    (md/deferred? x) deref))

(defn with-system
  "Starts the system passes it to `f` and then shuts it down afterwards."
  [sys f]
  (let [sys (co/start sys)
        stop (fn []
               (log/debug "Stopping system")
               (co/stop sys))]
    (try
      ;; If `f` returns a deferred, deref it first
      (let [r (maybe-deref (f sys))]
        (stop)
        r)
      (catch Exception ex
        (co/stop sys)
        (throw ex)))))

(defn with-runtime
  "Starts the given component system and then passes the `runtime` 
   component from the started system to `f`.  When complete, shuts 
   down the system."
  [sys f]
  (with-system sys (comp f :runtime)))
