(ns monkey.ci.commands
  "Event handlers for commands"
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as ca]))

(defn build
  "Performs a build, using the runner from the context"
  [ctx]
  (let [r (:runner ctx)]
    (log/debug "Running build with runner" r)
    (r ctx)))

(defn http-server
  "Does nothing but return a channel that will never close.  The http server 
   should already be started by the component system."
  [ctx]
  (ca/chan))
