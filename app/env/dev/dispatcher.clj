(ns dispatcher
  (:require [com.stuartsierra.component :as co]
            [config :as c]
            [monkey.ci.dispatcher.runtime :as dr]))

(def default-config
  {:http {:port 3001}})

(defn run-dispatcher [conf]
  (-> (dr/make-system (merge default-config conf))
      (co/start)))

(defn stop-dispatcher [sys]
  (co/stop sys)
  nil)

(defonce dispatcher (atom nil))

(defn stop! []
  (when @dispatcher
    (swap! dispatcher stop-dispatcher)))

(defn run!
  ([conf]
   (stop!)
   (reset! dispatcher (run-dispatcher conf)))
  ([]
   (run! (select-keys @c/global-config [:mailman :storage]))))
