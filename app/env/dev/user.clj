(ns user
  (:require [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as sc]
            [monkey.ci
             [core :as c]
             [utils :as u]]
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
                                     :runner {:type :child}
                                     :log-dir (u/abs-path "target/logs")
                                     :containers {:type :podman}})
                     (sc/subsystem [:http])
                     (sc/start-system)))
  nil)

(defn generate-signature
  ([secret payload]
   (-> payload
       (mac/hash {:key secret :alg :hmac+sha256})
       (codecs/bytes->hex)))
  ([payload]
   (generate-signature secret payload)))

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
  [{:keys [url body secret]}]
  (let [url (or url (format "http://localhost:%d/webhook/github" (get-server-port)))
        body (or body (load-example-github-webhook))
        json (json/generate-string body {:key-fn (comp csk/->camelCase name)})]
    (-> (client/post url
                     {:body json
                      :headers (cond-> {"content-type" "application/json"}
                                 secret (assoc "x-hub-signature-256" (str "sha256=" (generate-signature secret json))))})
        deref
        :status)))

(defn make-webhook-body
  "Creates a body object that can be used in a webhook, that holds minimal information
   required to trigger a build."
  [{:keys [url branch id] :or {branch "main"}}]
  {:repository {:master-branch branch
                :clone-url url}
   :head-commit {:id id}})
