(ns user
  (:require [com.stuartsierra.component :as sc]
            [monkey.ci.core :as c]
            [buddy.core
             [codecs :as codecs]
             [mac :as mac]]))

(defonce server (atom nil))

(defn stop-server []
  (swap! server (fn [s]
                  (when s
                    (sc/stop-system s))
                  nil)))

(def secret "test-secret")

(defn start-server []
  (stop-server)
  (reset! server (-> c/base-system
                     (assoc :config {:dev-mode true
                                     :github {:secret secret}})
                     (sc/subsystem [:http])
                     (sc/start-system))))

(defn generate-signature [payload]
  (-> payload
      (mac/hash {:key secret :alg :hmac+sha256})
      (codecs/bytes->hex)))
