(ns monkey.ci.web.script-api
  "API functionality that can be accessed by the build script.  This can either
   be part of the public API, or exposed through a UDS from the build runner.  The
   script API does not have security on its own, since the implementation will
   take care of this.  The script API exposes an OpenAPI spec, which is then
   fetched by the script, so it knows which services are available."
  (:require [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [monkey.ci
             [events :as e]
             [storage :as st]]
            [monkey.ci.web
             [api :as api]
             [common :as c]]
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
                   (if (and bus (e/post-event bus evt))
                     202
                     500))
                 (ca/<!!))}))

(def edn #{"application/edn"})

(def routes ["/script" {:swagger {:id :monkeyci/script-api}}
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

(defn- with-api
  "Replaces the public api factory function with its result"
  [ctx]
  (update ctx :public-api (fn [maker]
                            (maker ctx))))

(defn make-router
  ([opts routes]
   (ring/router
    routes
    {:data {:middleware c/default-middleware
            :muuntaja (c/make-muuntaja)
            :coercion reitit.coercion.schema/coercion
            ::c/context (with-api opts)}}))
  ([opts]
   (make-router opts routes)))

(defn make-app [opts]
  (c/make-app (make-router opts)))

(defn ^http/IHttpServer start-server
  "Starts a http server using the given options to configure the routes and the server.
   Returns an `org.httpkit.server.IHttpServer` object."
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

(defn local-api
  "Local api implementation.  This is used as a backend for the script API server
   when it is run locally.  It retrieves all information directly from local storage."
  [{:keys [storage] :as ctx}]
  (let [build-sid (get-in ctx [:build :sid])
        handlers {:get-params (fn []
                                (log/debug "Fetching all build params for sid" build-sid)
                                (->> (api/fetch-all-params storage (butlast build-sid))
                                     (map (juxt :name :value))
                                     (into {})))}]
    (fn [ep]
      (when-let [h (get handlers ep)]
        (h)))))
