(ns monkey.ci.build.api
  "Functions for invoking the build script API."
  (:require [aleph.http :as http]
            [aleph.http.client-middleware :as mw]
            [clojure.tools.logging :as log]
            [monkey.ci.build :as b]))

(defn as-edn [req]
  (-> req
      (assoc :accept :edn
             :as :clojure)))

(def api-middleware
  [mw/wrap-method
   mw/wrap-url
   mw/wrap-oauth
   mw/wrap-accept
   mw/wrap-query-params
   mw/wrap-content-type
   mw/wrap-exceptions])

(defn make-client
  "Creates a new api client function for the given url.  It returns a function
   that requires a request object that will send a http request.  The function 
   returns a deferred with the result body.  An authentication token is required."
  [url token]
  (letfn [(build-request [req]
            (assoc req
                   :url (str url (:path req))
                   :oauth-token token
                   :middelware api-middleware))]
    (fn [req]
      (-> req
          (build-request)
          (http/request)))))

(def ctx->api-client (comp :client :api))
(def ^:deprecated rt->api-client ctx->api-client)

(defn- fetch-params* [ctx]
  (let [client (ctx->api-client ctx)]
    (log/debug "Fetching repo params for" (b/get-sid ctx))
    (->> @(client {:path "/params"
                   :method :get})
         :body
         (map (juxt :name :value))
         (into {}))))

;; Use memoize because we'll only want to fetch them once
(def build-params (memoize fetch-params*))

(defn download-artifact
  "Downloads the artifact with given id for the current job.  Returns an input
   stream that can then be written to disk, or unzipped using archive functions."
  [ctx id]
  (let [client (ctx->api-client ctx)]
    (log/debug "Downloading artifact for build" (b/get-sid ctx) ":" id)
    (-> @(client {:path (str "/artifact/" id)
                  :method :get})
        :body)))
