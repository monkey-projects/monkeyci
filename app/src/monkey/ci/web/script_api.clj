(ns monkey.ci.web.script-api
  "API functionality that can be accessed by the build script.  This can either
   be part of the public API, or exposed through a UDS from the build runner.  The
   script API does not have security on its own, since the implementation will
   take care of this.  The script API exposes an OpenAPI spec, which is then
   fetched by the script, so it knows which services are available."
  (:require [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [monkey.ci.events :as e]
            [monkey.ci.web.common :as c]
            [monkey.socket-async.uds :as uds]
            [org.httpkit.server :as http]
            [reitit.coercion.schema]
            [reitit
             [ring :as ring]
             [swagger :as swagger]]
            [ring.util.response :as rur]
            [schema.core :as s])
  (:import java.nio.channels.ServerSocketChannel))

(defn- invoke-public-api
  "Invokes given endpoint on the public api"
  [req ep]
  (let [f (c/from-context req :public-api)]
    (f ep)))

(defn get-params [req]
  (rur/response (invoke-public-api req :get-params)))

(defn post-event [req]
  (let [evt (get-in req [:parameters :body])
        bus (c/req->bus req)]
    {:status (-> (ca/go
                   (if (e/post-event bus evt)
                     202
                     500))
                 (ca/<!!))}))

(def routes ["/script" {:swagger {:id :monkeyci/script-api}}
             [["/swagger.json"
               {:no-doc true
                :get (swagger/create-swagger-handler)}]
              ["/params"
               {:get get-params
                :summary "Retrieve configured build parameters"
                :operationId :get-params
                :responses {200 {:body {s/Str s/Str}}}}]
              ["/event"
               {:post post-event
                :summary "Post an event to the bus"
                :operationId :post-event
                :parameters {:body {s/Keyword s/Any}}
                :responses {202 {}}}]]])

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
  (->> (assoc http
              :legacy-return-value? false)
       (http/run-server (make-app opts))))

(def stop-server (comp deref http/server-stop!))

(defn listen-at-socket
  "Starts a script api that is listening at given socket path"
  [path ctx]
  (log/info "Starting script API at socket" path)
  (let [http-opts {:address-finder #(uds/make-address path)
                   :channel-factory (fn [_] (ServerSocketChannel/open uds/unix-proto))}]
    (start-server (assoc ctx :http http-opts))))
