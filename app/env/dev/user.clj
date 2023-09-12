(ns user
  (:require [monkey.ci.web.handler :as wh]))

(defonce server (atom nil))

(defn stop-server []
  (swap! server (fn [s]
                  (when s
                    (s))
                  nil)))

(defn start-server []
  (stop-server)
  (reset! server (wh/start-server {})))
