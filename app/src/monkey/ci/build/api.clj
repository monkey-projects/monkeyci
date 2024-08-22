(ns monkey.ci.build.api
  "Functions for invoking the build script API."
  (:require [aleph
             [http :as http]
             [netty :as an]]
            [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [monkey.ci.build :as b]
            [monkey.ci
             [runtime :as rt]
             [spec :as spec]]
            [monkey.ci.spec.build]
            [monkey.ci.web.common :as c]
            [reitit
             [ring :as ring]
             [swagger :as swagger]]
            [ring.util.response :as rur]
            [schema.core :as s]))

(defn generate-token
  "Generates a new API security token.  This token can be set in the API server
   and should be passed on to the build script."
  []
  (str (random-uuid)))

(defn req->config
  "Gets the config map from the request"
  [req]
  (get-in req [:reitit.core/match :data ::config]))

(defn get-params [req]
  (let [conf (req->config req)]
    (rur/response ;; TODO invoke api or get from db storage
     )))

(defn get-ip-addr
  "Determines the ip address of this VM"
  []
  ;; TODO There could be more than one
  (.. (java.net.Inet4Address/getLocalHost) (getHostAddress)))

(defn post-event [req]
  (let [evt (get-in req [:parameters :body])]
    (log/debug "Received event from build script:" evt)
    (try 
      {:status (if (rt/post-events (c/req->rt req) evt)
                 202
                 500)}
      (catch Exception ex
        (log/error "Unable to dispatch event" ex)
        {:status 500}))))

(def edn #{"application/edn"})

(def routes ["" {:swagger {:id :monkeyci/build-api}}
             [["/swagger.json"
               {:no-doc true
                :get (swagger/create-swagger-handler)}]
              ["/params"
               {:get get-params
                :summary "Retrieve configured build parameters"
                :operationId :get-params
                :responses {200 {:body {s/Str s/Str}}}
                :produces edn}]
              ["/event"
               {:post post-event
                :summary "Post an event to the bus"
                :operationId :post-event
                :parameters {:body {s/Keyword s/Any}}
                :responses {202 {}}
                :consumes edn}]]])

(defn security-middleware
  "Middleware that checks if the authorization header matches the specified token"
  [handler token]
  (fn [req]
    (let [auth (get-in req [:headers "authorization"])]
      (if (= auth (str "Bearer " token))
        (handler req)
        (rur/status 401)))))

(defn make-router
  ([opts routes]
   (ring/router
    routes
    {:data {:middleware (concat [[security-middleware (:token opts)]]
                                c/default-middleware)
            :muuntaja (c/make-muuntaja)
            :coercion reitit.coercion.schema/coercion
            ::config opts}}))
  ([opts]
   (make-router opts routes)))

(defn make-app [opts]
  (c/make-app (make-router opts)))

(defn start-server
  "Starts a build API server with a randomly generated token.  Returns the server
   and token."
  [{:keys [port] :or {port 0} :as conf}]
  {:pre [(spec/valid? :api/config conf)]}
  (let [token (generate-token)
        srv (http/start-server
             (make-app (assoc conf :token token))
             {:port port})]
    {:server srv
     :port (an/port srv)
     :token token}))

(def rt->api-client (comp :client :api))

(defn- repo-path [rt]
  (apply format "/customer/%s/repo/%s" (b/get-sid rt)))

(defn- fetch-params* [rt]
  (let [client (rt->api-client rt)
        [cust-id repo-id] (b/get-sid rt)]
    (log/debug "Fetching repo params for" cust-id repo-id)
    (->> @(client {:url (str (repo-path rt) "/param")
                   :method :get})
         (map (juxt :name :value))
         (into {}))))

;; Use memoize because we'll only want to fetch them once
(def build-params (memoize fetch-params*))

(defn download-artifact
  "Downloads the artifact with given id for the current job.  Returns an input
   stream that can then be written to disk, or unzipped using archive functions."
  [rt id]
  (let [client (rt->api-client rt)
        sid (b/get-sid rt)]
    (log/debug "Downloading artifact for build" sid ":" id)
    @(client {:url (format (str (repo-path rt) "/builds/%s/artifact/%s/download") (last sid) id)
              :method :get})))
