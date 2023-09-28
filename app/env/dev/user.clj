(ns user
  (:require [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as sc]
            [monkey.ci.core :as c]
            [buddy.core
             [codecs :as codecs]
             [mac :as mac]]
            [org.httpkit
             [client :as client]
             [server :as http]])
  (:import java.io.PushbackReader))

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
                                     :github {:secret secret}
                                     :args {:workdir "tmp"}
                                     :runner {:type :child}})
                     (sc/subsystem [:http])
                     (sc/start-system)))
  nil)

(defn generate-signature [payload]
  (-> payload
      (mac/hash {:key secret :alg :hmac+sha256})
      (codecs/bytes->hex)))

(defn load-example-github-webhook []
  (with-open [r (-> "test/example-github-webhook.edn"
                    (io/resource)
                    io/reader
                    PushbackReader.)]
    (edn/read r)))

(defn- get-server-port []
  (some-> server
          deref
          :http
          :server
          http/server-port))

(defn post-example-webhook
  "Posts example webhook to the local server"
  ([url]
   (let [body (-> (load-example-github-webhook)
                  (json/generate-string {:key-fn (comp csk/->camelCase name)}))]
     (-> (client/post url
                      {:body body
                       :headers {"content-type" "application/json"}})
         deref
         :status)))
  ([]
   (post-example-webhook (format "http://localhost:%d/webhook/github" (get-server-port)))))
