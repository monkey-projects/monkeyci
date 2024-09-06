(ns monkey.ci.runtime.common
  (:require [com.stuartsierra.component :as co]
            [manifold.deferred :as md]))

(defn with-system
  "Starts the system passes it to `f` and then shuts it down afterwards."
  [sys f]
  (let [sys (co/start sys)]
    (try
      (let [r (f sys)]
        ;; If `f` returns a deferred, only stop the system on realization
        (if (md/deferred? r)
          (md/finally r #(co/stop sys))
          (do 
            ;; Otherwise stop now
            (co/stop sys)
            r)))
      (catch Exception ex
        (co/stop sys)
        (throw ex)))))

(defn with-runtime
  "Starts the given component system and then passes the `runtime` 
   component from the started system to `f`.  When complete, shuts 
   down the system."
  [sys f]
  (with-system sys (comp f :runtime)))
