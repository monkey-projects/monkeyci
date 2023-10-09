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
             [storage :as s]
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
                                         :id (get-in req [:path-params :id])
                                         :payload (:body-params req)}))
                200
                500)})))

(defn prepare-build
  "Event handler that looks up details for the given github webhook.  If the webhook 
   refers to a valid configuration, a build id is created and a new event is launched,
   which in turn should start the build runner."
  [{st :storage} {:keys [id payload] :as evt}]
  (if-let [details (s/find-details-for-webhook st id)]
    (let [{:keys [master-branch clone-url]} (:repository payload)
          build-id (u/new-build-id)
          conf {:git {:url clone-url
                      :branch master-branch
                      :id (get-in payload [:head-commit :id])}
                :build-id build-id}]
      (when (s/create-build-metadata st (-> details
                                            (dissoc :id)
                                            (assoc :webhook-id id
                                                   :build-id (:build-id conf)
                                                   :commit-id (get-in conf [:git :id]))))
        {:type :webhook/validated
         :details details
         :build conf}))
    (log/warn "No webhook configuration found for" id)))
