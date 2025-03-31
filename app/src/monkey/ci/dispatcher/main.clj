(ns monkey.ci.dispatcher.main
  "Main class for the dispatcher, used when running as a microservice"
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [monkey.ci.config :as c]
            [monkey.ci.dispatcher.runtime :as dr]
            [monkey.ci.runtime.common :as rc]
            [monkey.ci.web.http :as wh]))

(defn -main [& args]
  (rc/with-system
    (dr/make-system (merge {:http {:port 3001}}
                           (c/load-config-file (first args))))
    (fn [{:keys [http-server]}]
      (log/info "Dispatcher server started")
      (wh/on-server-close http-server)))
  (log/info "Dispatcher server terminated"))
