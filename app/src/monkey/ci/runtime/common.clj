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
      (maybe-deref (f sys))
      (catch Throwable ex
        (log/error "Got exception in system:" ex)
        (throw ex))
      (finally
        (stop)))))

(defn with-system-async
  "Same as `with-system` but assumes `f` returns a deferred that is not being `deref`ed
   but wrapped so the system is stopped when the deferred is realized."
  [sys f]
  (let [sys (co/start sys)
        stop (fn []
               (log/debug "Stopping system")
               (co/stop sys))]
    (md/finally (f sys) stop)))

(defn with-runtime
  "Starts the given component system and then passes the `runtime` 
   component from the started system to `f`.  When complete, shuts 
   down the system."
  [sys f]
  (with-system sys (comp f :runtime)))
