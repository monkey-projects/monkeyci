(ns monkey.ci.script.api-client
  "Functions for invoking the build api HTTP server"
  (:require [clojure.edn :as edn]
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
  ;; TODO Smarter caching
  (memoize decrypt-key*))

(defn- fetch-params [ctx]
  (let [client (ctx->api-client ctx)]
    (log/debug "Fetching repo params")
    (->> (client {:path "/params"
                  :method :get})
         :body
         slurp
         (edn/read-string)
         (map (juxt :name :value))
         (into {}))))

(def build-params
  "Retrieves the params for this build.  This fetches the parameters from the
   API, and adds to them any additional parameters that have been specified on
   the build itself.  Since these are in encrypted form, we need to decrypt
   them here."
  ;; Use memoize because we'll only want to fetch them once
  (memoize fetch-params))
