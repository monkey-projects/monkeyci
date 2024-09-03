(ns monkey.ci.runtime.common
  (:require [com.stuartsierra.component :as co]))

(defn with-runtime
  "Starts the given component system and then passes the `runtime` 
   component from the started system to `f`.  When complete, shuts 
   down the system."
  [sys f]
  (let [sys (co/start sys)]
    (try
      (f (:runtime sys))
      (finally
        (co/stop sys)))))
