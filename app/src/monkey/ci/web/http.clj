(ns monkey.ci.web.http
  "Http server component"
  (:require [aleph
             [http :as aleph]
             [netty :as netty]]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [ring.util.response :as rur]))

(defn start-server
  "Starts http server.  Returns a server object that can be passed to
   `stop-server`."
  [config app]
  (let [http-opts (merge {:port 3000} config)]
    (log/info "Starting HTTP server at port" (:port http-opts))
    (aleph/start-server (:handler app) http-opts)))

(defn stop-server [s]
  (when s
    (log/info "Shutting down HTTP server...")
    (.close s)))

(defrecord HttpServer [config app]
  co/Lifecycle
  (start [this]
    (assoc this :server (start-server config app)))
  (stop [{:keys [server] :as this}]
    (when server
      (stop-server server))
    (dissoc this :server))
  
  clojure.lang.IFn
  (invoke [this]
    (co/stop this)))

(defn on-server-close
  "Returns a deferred that resolves when the server shuts down."
  [server]
  (md/future (netty/wait-for-close (:server server))))

(defn text-response [txt]
  (-> (rur/response txt)
      (rur/content-type "text/plain")))
