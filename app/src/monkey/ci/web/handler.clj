(ns monkey.ci.web.handler
  "Handler for the web server"
  (:require [clojure.tools.logging :as log]
            [medley.core :refer [update-existing]]
            [monkey.ci.web.github :as github]
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

(defn- maybe-generate [s g]
  (if (some? s)
    s
    (let [gen (g)]
      (log/info "Generated secret key:" gen)
      gen)))

(def routes
  [["/health" {:get health}]
   ["/webhook/github" {:post github-webhook
                       :middleware [:github-security]}]])

(defn- stringify-body
  "Since the raw body could be read more than once (security, content negotation...),
   this interceptor replaces it with a string that can be read multiple times."
  [h]
  (fn [req]
    (-> req
        (update-existing :body (fn [s]
                                 (when s (slurp s))))
        (h))))

(defn make-router [opts]
  (ring/router
   routes
   {:data {:middleware [stringify-body
                        rrmp/parameters-middleware
                        rrmm/format-middleware]
           :muuntaja mc/instance}
    :reitit.middleware/registry
    {:github-security [github/validate-security (maybe-generate
                                                 (get-in opts [:github :secret])
                                                 github/generate-secret-key)]}}))

(defn make-app [opts]
  (ring/ring-handler
   (make-router opts)
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
    (http/run-server (make-app opts)
                     (merge opts default-http-opts))))

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
