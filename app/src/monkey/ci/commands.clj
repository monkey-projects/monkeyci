(ns monkey.ci.commands
  "Event handlers for commands"
  (:require [clojure.tools.logging :as log]))

(defmulti handle-command :command)

(defmethod handle-command :build [evt]
  ;; TODO Start the build
  {:type :command/completed
   :command :build})

(defmethod handle-command :http [_]
  ;; This command does nothing, the http server is started by the component
  (log/info "Http server started"))

;; Transducer use to dispatch command events
(def command-tx (comp (map handle-command)
                      (remove nil?)))
