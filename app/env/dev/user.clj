(ns user
  (:require [monkey.ci.web.handler :as wh]
            [buddy.core
             [codecs :as codecs]
             [mac :as mac]]))

(defonce server (atom nil))

(defn stop-server []
  (swap! server (fn [s]
                  (when s
                    (wh/stop-server s))
                  nil)))

(def secret "test-secret")

(defn start-server []
  (stop-server)
  (reset! server (wh/start-server {:github {:secret secret}})))

(defn generate-signature [payload]
  (-> payload
      (mac/hash {:key secret :alg :hmac+sha256})
      (codecs/bytes->hex)))
