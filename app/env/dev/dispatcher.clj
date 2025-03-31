(ns dispatcher
  (:require [com.stuartsierra.component :as co]
            [config :as c]
            [monkey.ci.dispatcher.runtime :as dr]
            [monkey.ci.events.mailman :as em]))

(def default-config
  {:http {:port 3003}})

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

(defn run-dispatcher!
  ([conf]
   (stop!)
   (reset! dispatcher (run-dispatcher conf)))
  ([]
   (run! (select-keys @c/global-config [:mailman :storage]))))

(defn run-staging! []
  (-> (c/load-config "oci/staging-config.edn")
      (run-dispatcher!)))

(defn post-event! [evt]
  (let [mm (:mailman @dispatcher)]
    (em/post-events mm [evt])))
