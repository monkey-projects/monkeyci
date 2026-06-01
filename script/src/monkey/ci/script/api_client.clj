(ns monkey.ci.script.api-client
  "Functions for invoking the build api HTTP server"
  (:require [clojure.core.async :as ca]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [monkey.ci.errors :as err]
            [org.httpkit.client :as http]))

(defn api-request
  "Sends a request to the api at configured url"
  [{:keys [url token] :as opts} req]
  (letfn [(build-request [req]
            (-> req
                (merge (dissoc opts [:url :token]))
                (assoc :url (str url (:path req))
                       :oauth-token token)))]
    (-> req
        (build-request)
        (http/request))))

(defn make-async-client
  "Creates a new api client function for the given url.  It returns a function
   that requires a request object that will send a http request.  The function 
   returns a promise with the result body.  Options:
    - :url   The url to connect to
    - :token The authentication token
   More options can be specified, they are directly passed to the request function."
  [opts]
  (partial api-request opts))

(defn- throw-on-error [resp]
  (if (<= 400 (:status resp))
    (throw (ex-info "Api request error" resp))
    resp))

(defn make-client
  "Same as `make-async-client` but `deref`s any requests and throws exceptions
   on errors."
  [opts]
  (let [c (make-async-client opts)]
    (fn [req]
      (-> (c req)
          (deref)
          (throw-on-error)))))

(def ctx->api-client (comp :client :api))

(defn decrypt-key* [client enc-dek]
  (let [p (pr-str enc-dek)]
    (-> (client {:path "/decrypt-key"
                 :method :post
                 :body p
                 :headers {:content-type "application/edn"
                           :content-length (str (count p))}})
        :body
        slurp)))

(def decrypt-key
  "Given an encrypted data encryption key, decrypts it by sending a decryption
   request to the build api server."
  (memoize decrypt-key*))

(defn- body->edn [v]
  (some-> v
          slurp
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

(defn get-events
  "Opens a stream that will receive all events for the build."
  [client]
  (let [prefix "data: "
        parse-evt (fn [l]
                    (when (cs/starts-with? l prefix)
                      (edn/read-string (subs l (count prefix)))))
        c (ca/chan)
        r (->> (client {:path "/events"
                        :method :get})
               :body
               (io/reader)
               (line-seq))]
    (ca/go-loop [n r]
      (if-let [evt (first n)]
        (do
          (when-let [p (parse-evt evt)]
            (ca/>! c p))
          (recur (rest n)))
        ;; No more data to read, close channel
        (ca/close! c)))
    c))
