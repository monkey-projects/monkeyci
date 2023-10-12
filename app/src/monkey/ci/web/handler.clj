(ns monkey.ci.web.handler
  "Handler for the web server"
  (:require [camel-snake-kebab.core :as csk]
            [clojure.tools.logging :as log]
            [medley.core :refer [update-existing]]
            [monkey.ci.events :as e]
            [monkey.ci.web
             [api :as api]
             [github :as github]]
            [muuntaja.core :as mc]
            [org.httpkit.server :as http]
            [reitit.coercion :as rc]
            [reitit.coercion.schema]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware
             [muuntaja :as rrmm]
             [parameters :as rrmp]]
            [schema.core :as s]))

(defn health [_]
  ;; TODO Make this more meaningful
  {:status 200
   :body "ok"
   :headers {"Content-Type" "text/plain"}})

(defn- maybe-generate
  "If no secret key has been configured, generate one and log it.  Don't
   use this in production!"
  [s g]
  (if (some? s)
    s
    (let [gen (g)]
      (log/info "Generated secret key:" gen)
      gen)))

(def Id s/Str)

(s/defschema NewCustomer
  {:name s/Str})

(s/defschema NewProject
  {:customer-id Id
   :name s/Str})

(s/defschema NewWebhook
  {:customer-id Id
   :project-id Id
   :repo-id Id})

(def webhook-routes
  ["/webhook"
   [["/github/:id" {:post {:handler github/webhook
                           :parameters {:path {:id Id}
                                        :body s/Any}}
                    :middleware [:github-security]}]
    ["" {:post {:handler api/create-webhook
                :parameters {:body NewWebhook}}}]
    ["/:webhook-id" {:get {:handler api/get-webhook}
                     :put {:handler api/update-webhook}
                     :parameters {:path {:webhook-id Id}}}]]])

(def project-routes
  ["/project"
   [["" {:post {:handler api/create-project
                :parameters {:body NewProject}}}]
    ["/:project-id" {:get {:handler api/get-project}
                     :put {:handler api/update-project}
                     :parameters {:path {:project-id Id}}}]]])

(def customer-routes
  ["/customer"
   [["" {:post {:handler api/create-customer
                :parameters {:body NewCustomer}}}]
    ["/:customer-id"
     {:parameters {:path {:customer-id Id}}}
     [[""
       {:get {:handler api/get-customer}
        :put {:handler api/update-customer}}]
      [project-routes]]]]])

(def routes
  [["/health" {:get health}]
   webhook-routes
   customer-routes])

(defn- stringify-body
  "Since the raw body could be read more than once (security, content negotation...),
   this interceptor replaces it with a string that can be read multiple times.  This
   should only be used for requests that have reasonably small bodies!  In other
   cases, the body could be written to a temp file."
  [h]
  (fn [req]
    (-> req
        (update-existing :body (fn [s]
                                 (when (instance? java.io.InputStream s)
                                   (slurp s))))
        (h))))

(defn- passthrough-middleware
  "No-op middleware, just passes the request to the parent handler."
  [h]
  (fn [req]
    (h req)))

(defn make-router
  ([{:keys [dev-mode] :as opts} routes]
   (ring/router
    routes
    {:data {:middleware [stringify-body
                         rrmp/parameters-middleware
                         rrmm/format-middleware
                         rrc/coerce-exceptions-middleware
                         rrc/coerce-request-middleware
                         rrc/coerce-response-middleware]
            :muuntaja (mc/create
                       (assoc-in mc/default-options
                                 ;; Convert keys to kebab-case
                                 [:formats "application/json" :decoder-opts]
                                 {:decode-key-fn csk/->kebab-case-keyword}))
            :coercion reitit.coercion.schema/coercion
            ::context opts}
     ;; Disabled, results in 405 errors for some reason
     ;;:compile rc/compile-request-coercers
     :reitit.middleware/registry
     {:github-security (if dev-mode
                         ;; Disable security in dev mode
                         [passthrough-middleware]
                         [github/validate-security (maybe-generate
                                                    (get-in opts [:github :secret])
                                                    github/generate-secret-key)])}}))
  ([opts]
   (make-router opts routes)))

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
  (let [http-opts (merge {:port 3000} (:http opts))]
    (log/info "Starting HTTP server at port" (:port http-opts))
    (http/run-server (make-app opts)
                     (merge http-opts default-http-opts))))

(defn stop-server [s]
  (when s
    (log/info "Shutting down HTTP server...")
    (http/server-stop! s)))

