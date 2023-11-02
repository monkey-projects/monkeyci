(ns user
  (:require [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [clojure.core.async :as ca]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as sc]
            [config.core :as cc]
            [monkey.ci
             [config :as config]
             [core :as c]
             [events :as e]
             [storage :as s]
             [utils :as u]]
            [monkey.ci.storage.oci]
            #_[monkey.ci.test.examples-test :as et]
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

(defn start-server []
  (stop-server)
  (reset! server (-> c/base-system                     
                     (assoc :config (-> (config/app-config cc/env {:dev-mode true :workdir "tmp"})
                                        (assoc :log-dir (u/abs-path "target/logs"))))
                     (sc/subsystem [:http])
                     (sc/start-system)))
  nil)

(defn generate-signature
  [secret payload]
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
  [{:keys [url body secret]}]
  (let [url (or url (format "http://localhost:%d/webhook/github/test-hook" (get-server-port)))
        body (or body (load-example-github-webhook))
        json (json/generate-string body {:key-fn (comp csk/->camelCase name)})]
    (-> (client/post
         url
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

(defn make-private-webhook-body
  "Creates webhook body but for private repos."
  [{:keys [url branch id] :or {branch "main"}}]
  {:repository {:master-branch branch
                :ssh-url url
                :private true}
   :head-commit {:id id}})

#_(defn run-example [ex-name]
  (ca/<!! (et/run-example ex-name)))

(defn post-event
  "Posts event in the current server bus"
  [evt]
  (-> (get-in @server [:context :event-bus])
      (e/post-event evt)))

(defn- load-edn [f]
  (with-open [is (PushbackReader. (io/reader f))]
    (edn/read is)))

(defn load-config [f]
  (-> (load-edn (io/file "dev-resources" f))
      (update-in [:storage :credentials :private-key] u/load-privkey)))

(defn- migrate-dir [p sid st]
  (let [files (seq (.listFiles p))
        sid-name (fn [f]
                   (let [n (.getName f)
                         l (.lastIndexOf n ".")]
                     (subs n 0 l)))
        make-sid (fn [f]
                   (concat sid [(sid-name f)]))]
    (doseq [f files]
      (cond
        (.isFile f)
        (do
          (log/info "Migrating:" f)
          (s/write-obj st (make-sid f) (load-edn f)))
        (.isDirectory f)
        (migrate-dir f (concat sid [(.getName f)]) st)))))

(defn migrate-storage
  "Migrates all files from given directory to destiny storage."
  [dir st]
  (let [f (io/file dir)]
    (log/info "Migrating storage from" (.getCanonicalPath f))
    (migrate-dir f [] st)))
