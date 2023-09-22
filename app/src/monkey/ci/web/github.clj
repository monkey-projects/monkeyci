(ns monkey.ci.web.github
  "Functionality specific for Github"
  (:require [buddy.core
             [codecs :as codecs]
             [mac :as mac]
             [nonce :as nonce]]
            [clojure.core.async :refer [go <!]]
            [clojure.tools.logging :as log]
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
  (log/info "Body params:" (prn-str (:body-params req)))
  (log/debug "Request data:" (get-in req [:reitit.core/match :data]))
  (go
    {:status (if (<! (c/post-event req {:type :webhook/github
                                        :payload (:body-params req)}))
               200
               500)}))
