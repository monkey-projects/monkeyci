(ns monkey.ci.build.api
  "Functions for invoking the build script API."
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [monkey.ci.build :as b]
            [monkey.martian.aleph :as mma]))

(defn- token->auth-headers [token]
  {"Authorization" (str "Bearer " token)})

(defn authorization-interceptor [token]
  {:name ::authorization
   :enter (fn [ctx]
            (update-in ctx [:request :headers] merge (token->auth-headers token)))})

(def process-response
  {:name ::process-response
   :leave (fn [{resp :response :as ctx}]
            (if (>= (:status resp) 400)
              (throw (ex-info "Got error response" resp))
              ;; Unwrap the response
              (update ctx :response :body)))})

(defn make-client
  "Creates a new api client using Martian for the given url.  It downloads
   the swagger page and automatically configures available routes.  An
   authentication token is required."
  [url token]
  (mma/bootstrap-openapi
   url
   (update mma/default-opts :interceptors (partial concat [(authorization-interceptor token)
                                                           process-response]))
   {:headers (token->auth-headers token)}))

(def ctx->api-client (comp :client :api))
(def ^:deprecated rt->api-client ctx->api-client)

(defn- repo-path [ctx]
  (apply format "/customer/%s/repo/%s" (b/get-sid ctx)))

(defn- fetch-params* [ctx]
  (let [client (ctx->api-client ctx)
        [cust-id repo-id] (b/get-sid ctx)]
    (log/debug "Fetching repo params for" cust-id repo-id)
    (->> @(client {:url (str (repo-path ctx) "/param")
                   :method :get})
         (map (juxt :name :value))
         (into {}))))

;; Use memoize because we'll only want to fetch them once
(def build-params (memoize fetch-params*))

(defn download-artifact
  "Downloads the artifact with given id for the current job.  Returns an input
   stream that can then be written to disk, or unzipped using archive functions."
  [ctx id]
  (let [client (ctx->api-client ctx)
        sid (b/get-sid ctx)]
    (log/debug "Downloading artifact for build" sid ":" id)
    @(client {:url (format (str (repo-path ctx) "/builds/%s/artifact/%s/download") (last sid) id)
              :method :get})))
