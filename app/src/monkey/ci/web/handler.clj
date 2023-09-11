(ns monkey.ci.web.handler
  "Handler for the web server"
  (:require [clojure.tools.logging :as log]
            [org.httpkit.server :as http]))

(def handler (constantly {:body "ok"
                          :headers {"Content-Type" "text/plain"}
                          :status 200}))

(defn start-server [opts]
  (let [opts (merge {:port 3000} opts)]
    (log/info "Starting HTTP server at port" (:port opts))
    (http/run-server handler opts)))
