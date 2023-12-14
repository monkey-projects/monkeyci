(ns monkey.ci.braid.core
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [monkey.braid.core :as mbc]))

(defn handle-msg [msg]
  (log/info "Handling message:" msg))

(defn -main [& args]
  (mbc/start-bot-server
   (mbc/env->config)
   handle-msg))
