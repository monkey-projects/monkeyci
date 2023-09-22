(ns monkey.ci.commands
  "Event handlers for commands"
  (:require [clojure.tools.logging :as log]))

(defmulti handle-command :command)

(defmethod handle-command :build [evt]
  ;; Create a new event that will start the build.  Depending on
  ;; configuration, a runner will handle this event.
  (assoc evt
         :type :build/started
         ;; TODO Generate a more useful build id
         :build-id (format "build-%d" (System/currentTimeMillis))))

(defmethod handle-command :http [_]
  ;; This command does nothing, the http server is started by the component
  (log/info "Http server started"))

;; Transducer use to dispatch command events
(def command-tx (comp (map handle-command)
                      (remove nil?)))
