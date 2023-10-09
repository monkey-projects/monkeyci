(ns monkey.ci.web.github
  "Functionality specific for Github"
  (:require [buddy.core
             [codecs :as codecs]
             [mac :as mac]
             [nonce :as nonce]]
            [clojure.core.async :refer [go <!! <!]]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.ci
             [context :as ctx]
             [utils :as u]]
            [monkey.ci.web.common :as c]))

(defn extract-signature [s]
  (when s
    (let [[k v :as parts] (seq (.split s "="))]
      (when (and (= 2 (count parts)) (= "sha256" k))
        v))))

(defn valid-security?
  "Validates security header"
  [{:keys [secret payload x-hub-signature]}]
  (when-let [sign (extract-signature x-hub-signature)]
    (mac/verify payload
              (codecs/hex->bytes sign)
              {:key secret :alg :hmac+sha256})))

(defn validate-security
  "Middleware that validates the github security header"
  [h secret]
  (fn [req]
    (if (valid-security? {:secret secret
                          :payload (:body req)
                          :x-hub-signature (get-in req [:headers "x-hub-signature-256"])})
      (h req)
      {:status 401
       :body "Invalid signature header"})))

(defn generate-secret-key []
  (-> (nonce/random-nonce 32)
      (codecs/bytes->hex)))

(defn webhook
  "Receives an incoming webhook from Github.  This actually just posts
   the event on the internal bus and returns a 200 OK response."
  [req]
  (log/trace "Got incoming webhook with body:" (prn-str (:body-params req)))
  ;; Httpkit can't handle channels so read it here
  (<!!
   (go
     {:status (if (<! (c/post-event req {:type :webhook/github
                                         :payload (:body-params req)}))
                200
                500)})))

(defn build
  "Handles webhook build event by preparing the config to actually
   launch the build script.  The build runner performs the repo clone and
   checkout and runs the script."
  [{:keys [runner] :as ctx}]
  (let [p (get-in ctx [:event :payload])
        {:keys [master-branch clone-url]} (:repository p)
        build-id (u/new-build-id)
        ;; Use combination of work dir and build id for checkout
        workdir (ctx/checkout-dir ctx build-id)
        conf {:git {:url clone-url
                    :branch master-branch
                    :id (get-in p [:head-commit :id])
                    :dir workdir}
              :build-id build-id}]
    (log/debug "Starting build with config:" conf)
    (-> ctx
        (assoc :build conf)
        (runner))))
