(ns monkey.ci.web.handler
  "Handler for the web server"
  (:require [clojure.tools.logging :as log]
            [muuntaja.core :as mc]
            [org.httpkit.server :as http]
            [reitit.ring :as ring]
            [reitit.ring.middleware
             [muuntaja :as rrmm]
             [parameters :as rrmp]]))

(defn health [_]
  ;; TODO Make this more meaningful
  {:status 200
   :body "ok"
   :headers {"Content-Type" "text/plain"}})

(defn github-webhook [req]
  ;; TODO
  (log/info "Body params:" (:body-params req))
  {:status 200})

(def router
  (ring/router
   [["/health" {:get health}]
    ["/webhook/github" {:post github-webhook}]]
   {:data {:middleware [rrmp/parameters-middleware
                        rrmm/format-middleware]
           :muuntaja mc/instance}}))

(def app (ring/ring-handler
          router
          (ring/routes
           (ring/redirect-trailing-slash-handler)
           (ring/create-default-handler))))

(def default-http-opts
  ;; Virtual threads are still a preview feature
  { ;;:worker-pool (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
   :legacy-return-value? false})

(defn start-server
  "Starts http server.  Returns a server object that can be passed to
   `stop-server`."
  [opts]
  (let [opts (merge {:port 3000} opts)]
    (log/info "Starting HTTP server at port" (:port opts))
    (http/run-server app (merge opts default-http-opts))))

(defn stop-server [s]
  (when s
    (log/info "Shutting down HTTP server...")
    (http/server-stop! s)))

(defn wait-until-stopped
  "Loops until the web server has stopped."
  [s]
  (loop [st (http/server-status s)]
    (when (not= :stopped st)
      (Thread/sleep 100)
      (recur (http/server-status s)))))
