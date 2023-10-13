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
            #_[reitit.coercion :as rc]
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

(defn- assoc-id [s]
  (assoc s (s/optional-key :id) Id))

(s/defschema NewCustomer
  {:name s/Str})

(s/defschema UpdateCustomer
  (assoc-id NewCustomer))

(s/defschema NewProject
  {:customer-id Id
   :name s/Str})

(s/defschema UpdateProject
  (assoc-id NewProject))

(s/defschema NewWebhook
  {:customer-id Id
   :project-id Id
   :repo-id Id})

(s/defschema UpdateWebhook
  (assoc-id NewWebhook))

(s/defschema NewRepo
  {:customer-id Id
   :project-id Id
   :name s/Str
   :url s/Str})

(s/defschema UpdateRepo
  (assoc-id NewRepo))

(defn- generic-routes
  "Generates generic entity routes.  If child routes are given, they are added
   as additional routes after the full path."
  [{:keys [getter creator updater id-key new-schema update-schema child-routes]}]
  [["" {:post {:handler creator
               :parameters {:body new-schema}}}]
   [(str "/" id-key)
    {:parameters {:path {id-key Id}}}
    (cond-> [["" {:get {:handler getter}
                  :put {:handler updater
                        :parameters {:body update-schema}}}]]
      child-routes (conj [child-routes]))]])

(def webhook-routes
  ["/webhook"
   [["/github/:id" {:post {:handler github/webhook
                           :parameters {:path {:id Id}
                                        :body s/Any}}
                    :middleware [:github-security]}]
    ["" {:post {:handler api/create-webhook
                :parameters {:body NewWebhook}}}]
    ["/:webhook-id" {:get {:handler api/get-webhook}
                     :put {:handler api/update-webhook
                           :parameters {:body UpdateWebhook}}
                     :parameters {:path {:webhook-id Id}}}]]])

(def repo-routes
  ["/repo"
   (generic-routes
    {:creator api/create-repo
     :updater api/update-repo
     :getter  api/get-repo
     :new-schema NewRepo
     :update-schema UpdateRepo
     :id-key :repo-id})])

(def project-routes
  ["/project"
   (generic-routes
    {:creator api/create-project
     :updater api/update-project
     :getter  api/get-project
     :new-schema NewProject
     :update-schema UpdateProject
     :id-key :project-id
     :child-routes repo-routes})])

(def customer-routes
  ["/customer"
   (generic-routes
    {:creator api/create-customer
     :updater api/update-customer
     :getter  api/get-customer
     :new-schema NewCustomer
     :update-schema UpdateCustomer
     :id-key :customer-id
     :child-routes project-routes})])

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
                       (-> mc/default-options
                           (assoc-in 
                            ;; Convert keys to kebab-case
                            [:formats "application/json" :decoder-opts]
                            {:decode-key-fn csk/->kebab-case-keyword})
                           (assoc-in
                            [:formats "application/json" :encoder-opts]
                            {:encode-key-fn (comp csk/->camelCase name)})))
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

