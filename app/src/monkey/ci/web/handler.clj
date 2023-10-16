(ns monkey.ci.web.handler
  "Handler for the web server"
  (:require [clojure.tools.logging :as log]
            [medley.core :refer [update-existing]]
            [monkey.ci.events :as e]
            [monkey.ci.web
             [api :as api]
             [common :as c]
             [github :as github]]
            [org.httpkit.server :as http]
            #_[reitit.coercion :as rc]
            [reitit.coercion.schema]
            [reitit.ring :as ring]
            [schema.core :as s]))

(defn health [_]
  ;; TODO Make this more meaningful
  {:status 200
   :body "ok"
   :headers {"Content-Type" "text/plain"}})

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

(s/defschema Parameters
  [{:name s/Str
    :value s/Str}])

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
      child-routes (concat child-routes))]])

(def webhook-routes
  ["/webhook"
   (-> (generic-routes
        {:creator api/create-webhook
         :updater api/update-webhook
         :getter  api/get-webhook
         :new-schema NewWebhook
         :update-schema UpdateWebhook
         :id-key :webhook-id})
       (conj ["/github/:id" {:post {:handler github/webhook
                                    :parameters {:path {:id Id}
                                                 :body s/Any}}
                             :middleware [:github-security]}]))])

(def parameter-routes
  ["/param" {:get {:handler api/get-params}
             :put {:handler api/update-params
                   :parameters {:body Parameters}}}])

(def repo-routes
  ["/repo"
   (generic-routes
    {:creator api/create-repo
     :updater api/update-repo
     :getter  api/get-repo
     :new-schema NewRepo
     :update-schema UpdateRepo
     :id-key :repo-id
     :child-routes [parameter-routes]})])

(def project-routes
  ["/project"
   (generic-routes
    {:creator api/create-project
     :updater api/update-project
     :getter  api/get-project
     :new-schema NewProject
     :update-schema UpdateProject
     :id-key :project-id
     :child-routes [repo-routes
                    parameter-routes]})])

(def customer-routes
  ["/customer"
   (generic-routes
    {:creator api/create-customer
     :updater api/update-customer
     :getter  api/get-customer
     :new-schema NewCustomer
     :update-schema UpdateCustomer
     :id-key :customer-id
     :child-routes [project-routes
                    parameter-routes]})])

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
    {:data {:middleware (vec (concat [stringify-body]
                                     c/default-middleware))
            :muuntaja (c/make-muuntaja)
            :coercion reitit.coercion.schema/coercion
            ::context opts}
     ;; Disabled, results in 405 errors for some reason
     ;;:compile rc/compile-request-coercers
     :reitit.middleware/registry
     {:github-security (if dev-mode
                         ;; Disable security in dev mode
                         [passthrough-middleware]
                         [github/validate-security])}}))
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

