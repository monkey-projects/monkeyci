(ns monkey.ci.web.script-api
  "API functionality that can be accessed by the build script.  This can either
   be part of the public API, or exposed through a UDS from the build runner.  The
   script API does not have security on its own, since the implementation will
   take care of this.  The script API exposes an OpenAPI spec, which is then
   fetched by the script, so it knows which services are available."
  (:require [monkey.ci.web.common :as c]
            [org.httpkit.server :as http]
            [reitit
             [ring :as ring]
             [swagger :as swagger]]
            [ring.util.response :as rur]))

(defn- invoke-public-api
  "Invokes given endpoint on the public api"
  [req ep]
  (let [f (c/from-context req :public-api)]
    (f ep)))

(defn get-params [req]
  (rur/response (invoke-public-api req :get-params)))

(def routes ["/script"
             [["/swagger.json"
               {:no-doc true
                :get (swagger/create-swagger-handler)}]
              ["/params"
               {:get get-params
                :summary "Retrieve configured build parameters"
                :operationId :get-params}]]])

(defn make-router
  ([opts routes]
   (ring/router
    routes
    {:data {:middleware c/default-middleware
            :muuntaja (c/make-muuntaja)
            :coercion reitit.coercion.schema/coercion
            ::c/context opts}}))
  ([opts]
   (make-router opts routes)))

(defn make-app [opts]
  (c/make-app (make-router opts)))

(defn start-server
  "Starts a http server using the given options to configure the routes and the server."
  [{:keys [http] :as opts}]
  (http/run-server (make-app opts) http))
