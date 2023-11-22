(ns webhook
  (:require [buddy.core
             [codecs :as codecs]
             [mac :as mac]]
            [camel-snake-kebab.core :as csk]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [org.httpkit.client :as client]
            [server :refer [get-server-port]])
  (:import java.io.PushbackReader))

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
