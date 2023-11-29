(ns server
  (:require [com.stuartsierra.component :as sc]
            [config.core :as cc]
            [monkey.ci
             [config :as config]
             [core :as c]
             [events :as e]
             [utils :as u]]
            [org.httpkit.server :as http]))

(defonce server (atom nil))

(defn stop-server []
  (swap! server (fn [s]
                  (when s
                    (sc/stop-system s))
                  nil)))

(defn start-server []
  (stop-server)
  (reset! server (-> c/base-system                     
                     (assoc :config (-> (config/app-config cc/env {:dev-mode true :workdir "tmp"})
                                        (assoc :log-dir (u/abs-path "target/logs"))))
                     (sc/subsystem [:http])
                     (sc/start-system)))
  nil)

(defn get-server-port []
  (some-> server
          deref
          :http
          :server
          http/server-port))

(defn post-event
  "Posts event in the current server bus"
  [evt]
  (-> (get-in @server [:context :event-bus])
      (e/post-event evt)))
