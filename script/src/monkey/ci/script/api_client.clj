(ns monkey.ci.script.api-client
  "Functions for invoking the build api HTTP server"
  (:require [babashka.http-client :as http]
            [clojure.core.async :as ca]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [monkey.ci.errors :as err]))

(defn api-request
  "Sends a request to the api at configured url"
  [{:keys [url token] :as opts} req]
  (letfn [(build-request [req]
            (-> req
                (merge (dissoc opts [:url :token]))
                (assoc :uri (str url (:path req))
                       :oauth-token token)))]
    (-> req
        (build-request)
        (http/request))))

(defn- throw-on-error [{:keys [status] :as resp}]
  (if (or (nil? status) (<= 400 status))
    (throw (ex-info "Api request error" resp))
    resp))

(defn make-client
  "Creates a new api client function for the given url.  It returns a function
   that requires a request object that will send a http request.  The function 
   returns a result body.  Options:
    - :url   The url to connect to
    - :token The authentication token
   More options can be specified, they are directly passed to the request function."
  [opts]
  (fn [req]
    (-> (api-request opts req)
        (throw-on-error))))

(def ctx->api-client (comp :client :api))

(defn- body->str [x]
  (cond
    (string? x) x
    (some? x) (slurp x)
    :else nil))

(defn decrypt-key* [client enc-dek]
  (let [p (pr-str enc-dek)]
    (-> (client {:path "/decrypt-key"
                 :method :post
                 :body p
                 :headers {:content-type "application/edn"
                           :content-length (str (count p))}})
        :body
        body->str)))

(def decrypt-key
  "Given an encrypted data encryption key, decrypts it by sending a decryption
   request to the build api server."
  (memoize decrypt-key*))

(defn- body->edn [v]
  (some-> v
          (body->str)
          (edn/read-string)))

(defn- fetch-params [ctx]
  (let [client (ctx->api-client ctx)]
    (log/debug "Fetching repo params")
    (->> (client {:path "/params"
                  :method :get})
         :body
         (body->edn)
         (map (juxt :name :value))
         (into {}))))

(def build-params
  "Retrieves the params for this build.  This fetches the parameters from the
   API, and adds to them any additional parameters that have been specified on
   the build itself.  Since these are in encrypted form, we need to decrypt
   them here."
  ;; Use memoize because we'll only want to fetch them once
  (memoize fetch-params))

(defn- store-path [client req id src]
  (->> (client {:path (str "/" req "/" id)
                :method :post
                :body (pr-str {:path src})})
       :body
       (body->edn)
       :path))

(defn- restore-path [client req id dest]
  (->> (client {:path (str "/" req "/" id)
                :query-params {:path dest}
                :method :get})
       :body
       (body->edn)
       :path))

(defn get-artifact
  "Since scripts are often unable to unzip artifacts (e.g. if they run in babashka),
   downloading artifacts is not available.  Instead we request the server to copy
   the desired files to a specified location."
  [client art-id dest]
  (restore-path client "artifact" art-id dest))

(defn put-artifact
  "Similar to `get-artifact`, does not actually upload the artifact but does instruct
   the server to copy the files at given path to artifact storage."
  [client art-id src]
  (store-path client "artifact" art-id src))

(defn get-cache
  "Similar to `get-artifact`."
  [client art-id dest]
  (restore-path client "cache" art-id dest))

(defn put-cache
  "Similar to `put-artifact`"
  [client art-id src]
  (store-path client "cache" art-id src))

(defn push-events
  "Pushes events to the build api server, which is responsible for propagating
   them further up."
  [client evts]
  (-> (client {:path "/events"
               :method :post
               :body (pr-str evts)})
      :status
      (= 202)))

(defn- read-evt-stream [body c]
  (log/debug "Started reading event stream")
  (let [r (-> body
              (io/reader)
              (line-seq))
        prefix "data: "
        parse-evt (fn [l]
                    (when (cs/starts-with? l prefix)
                      (edn/read-string (subs l (count prefix)))))]
    (ca/go-loop [n r]
      (if-let [evt (first n)]
        (do
          (when-let [p (parse-evt evt)]
            (when (not= :script (:src p))
              (log/debug "Received streamed event:" (select-keys p [:type :src]))
              (ca/>! c p)))
          (recur (rest n)))
        ;; No more data to read, close channel
        (ca/close! c)))))

(defn get-events
  "Opens a stream that will receive all events for the build."
  [client]
  (let [c (ca/chan)
        body (-> (client {:path "/events"
                          :method :get
                          :as :stream})
                 :body)]
    (read-evt-stream body c)
    c))
