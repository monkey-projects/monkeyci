(ns monkey.ci.web.handler
  "Handler for the web server"
  (:require [aleph
             [http :as aleph]
             [netty :as netty]]
            [camel-snake-kebab.core :as csk]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [medley.core :refer [update-existing] :as mc]
            [monkey.ci
             [config :as config]
             [runtime :as rt]]
            [monkey.ci.web
             [api :as api]
             [auth :as auth]
             [common :as c]
             [github :as github]]
            [reitit.coercion.schema]
            [reitit.ring :as ring]
            [ring.middleware.cors :as cors]
            [schema.core :as s]))

(defn health [_]
  ;; TODO Make this more meaningful
  {:status 200
   :body "ok"
   :headers {"Content-Type" "text/plain"}})

(defn version [_]
  {:status 200
   :body (config/version)
   :headers {"Content-Type" "text/plain"}})

(def not-empty-str (s/constrained s/Str not-empty))
(def Id not-empty-str)
(def Name not-empty-str)

(defn- assoc-id [s]
  (assoc s (s/optional-key :id) Id))

(s/defschema Label
  {:name Name
   :value not-empty-str})

(s/defschema NewCustomer
  {:name Name})

(s/defschema UpdateCustomer
  (assoc-id NewCustomer))

(s/defschema NewWebhook
  {:customer-id Id
   :repo-id Id})

(s/defschema UpdateWebhook
  (assoc-id NewWebhook))

(s/defschema NewRepo
  {:customer-id Id
   :name Name
   :url s/Str
   (s/optional-key :labels) [Label]})

(s/defschema UpdateRepo
  (assoc-id NewRepo))

(s/defschema ParameterValue
  {:name s/Str
   :value s/Str})

(s/defschema LabelFilterConjunction
  {:label s/Str
   :value s/Str})

(s/defschema LabelFilter
  [LabelFilterConjunction])

(s/defschema Parameters
  {:parameters [ParameterValue]
   (s/optional-key :description) s/Str
   :label-filters [LabelFilter]})

(s/defschema SshKeys
  {:private-key s/Str
   :public-key s/Str ; TODO It may be possible to extract public key from private
   (s/optional-key :description) s/Str
   :label-filters [LabelFilter]})

(s/defschema User
  {:type s/Str
   :type-id s/Any
   (s/optional-key :id) Id ; Internal id
   (s/optional-key :email) s/Str
   (s/optional-key :customers) [Id]})

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

(def customer-parameter-routes
  ["/param" {:get {:handler api/get-customer-params}
             :put {:handler api/update-params
                   :parameters {:body [Parameters]}}}])

(def repo-parameter-routes
  ["/param" {:get {:handler api/get-repo-params}}])

(def customer-ssh-keys-routes
  ["/ssh-keys" {:get {:handler api/get-customer-ssh-keys}
                :put {:handler api/update-ssh-keys
                      :parameters {:body [SshKeys]}}}])

(def repo-ssh-keys-routes
  ["/ssh-keys" {:get {:handler api/get-repo-ssh-keys}}])

(def build-routes
  ["/builds"
   {:conflicting true}
   [["" {:get {:handler api/get-builds}}]
    ["/trigger"
     {:post {:handler api/trigger-build
             ;; TODO Read additional parameters from body instead
             :parameters {:query {(s/optional-key :branch) s/Str
                                  (s/optional-key :commit-id) s/Str}}}}]
    ["/latest"
     {:get {:handler api/get-latest-build}}]
    ["/:build-id"
     {:parameters {:path {:build-id Id}}}
     [[""
       {:get {:handler api/get-build}}]
      ["/logs"
       [[""
         {:get {:handler api/list-build-logs}}]
        ["/download"
         {:get {:handler api/download-build-log
                :parameters {:query {:path s/Str}}}}]]]]]]])

(def repo-routes
  ["/repo"
   (generic-routes
    {:creator api/create-repo
     :updater api/update-repo
     :getter  api/get-repo
     :new-schema NewRepo
     :update-schema UpdateRepo
     :id-key :repo-id
     :child-routes [repo-parameter-routes
                    repo-ssh-keys-routes
                    build-routes]})])

(def event-stream-routes
  ["/events" {:get {:handler api/event-stream
                    :parameters {:query {(s/optional-key :authorization) s/Str}}}}])

(def customer-routes
  ["/customer"
   {:middleware [:customer-check]}
   (generic-routes
    {:creator api/create-customer
     :updater api/update-customer
     :getter  api/get-customer
     :new-schema NewCustomer
     :update-schema UpdateCustomer
     :id-key :customer-id
     :child-routes [repo-routes
                    customer-parameter-routes
                    customer-ssh-keys-routes
                    event-stream-routes]})])

(def github-routes
  ["/github" [["/login" {:post
                         {:handler github/login
                          :parameters {:query {:code s/Str}}}}]
              ["/config" {:get
                          {:handler github/get-config}}]]])

(def auth-routes
  ["/auth/jwks" {:get
                 {:handler auth/jwks
                  :produces #{"application/json"}}}])

(def user-routes
  ["/user"
   [[""
     {:post
      {:handler api/create-user
       :parameters {:body User}}}]
    ["/:user-type/:type-id"
     {:parameters
      {:path {:user-type s/Str
              :type-id s/Str}}
      :get
      {:handler api/get-user}
      :put
      {:handler api/update-user
       :parameters {:body User}}}]]])

(def routes
  [["/health" {:get health}]
   ["/version" {:get version}]
   webhook-routes
   customer-routes
   github-routes
   auth-routes
   user-routes])

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

(defn- kebab-case-query
  "Middleware that converts any query params to kebab-case, to make them more idiomatic."
  [h]
  (fn [req]
    (-> req
        (mc/update-existing-in [:parameters :query] (partial mc/map-keys csk/->kebab-case-keyword))
        (h))))

(defn- log-request
  "Just logs the request, for monitoring or debugging purposes."
  [h]
  (fn [req]
    (log/info "Handling request:" (select-keys req [:uri :request-method :parameters]))
    (h req)))

(defn- passthrough-middleware
  "No-op middleware, just passes the request to the parent handler."
  [h]
  (fn [req]
    (h req)))

(defn make-router
  ([rt routes]
   (ring/router
    routes
    {:data {:middleware (vec (concat [stringify-body
                                      [cors/wrap-cors
                                       :access-control-allow-origin #".*"
                                       :access-control-allow-methods [:get :put :post :delete]
                                       :access-control-allow-credentials true]]
                                     c/default-middleware
                                     ;; TODO Authorization checks
                                     [kebab-case-query
                                      log-request]))
            :muuntaja (c/make-muuntaja)
            :coercion reitit.coercion.schema/coercion
            ::c/context rt ; TODO Remove this
            ::c/runtime rt}
     ;; Disabled, results in 405 errors for some reason
     ;;:compile rc/compile-request-coercers
     :reitit.middleware/registry
     {:github-security (if (rt/dev-mode? rt)
                         ;; Disable security in dev mode
                         [passthrough-middleware]
                         [github/validate-security])
      :customer-check (if (rt/dev-mode? rt)
                        [passthrough-middleware]
                        [auth/customer-authorization])}}))
  ([rt]
   (make-router rt routes)))

(defn make-app [rt]
  (-> (make-router rt)
      (c/make-app)
      (auth/secure-ring-app rt)))

(def default-http-opts
  ;; Virtual threads are still a preview feature
  { ;;:worker-pool (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
   :legacy-return-value? false})

(defn start-server
  "Starts http server.  Returns a server object that can be passed to
   `stop-server`."
  [rt]
  (let [http-opts (merge {:port 3000} (:http (rt/config rt)))]
    (log/info "Starting HTTP server at port" (:port http-opts))
    (aleph/start-server (make-app rt)
                        (merge http-opts default-http-opts))))

(defn stop-server [s]
  (when s
    (log/info "Shutting down HTTP server...")
    (.close s)))

(defmethod config/normalize-key :http [_ {:keys [args] :as conf}]
  (update-in conf [:http :port] #(or (:port args) %)))

(defrecord HttpServer [rt]
  co/Lifecycle
  (start [this]
    (assoc this :server (start-server rt)))
  (stop [{:keys [server] :as this}]
    (when server
      (stop-server server))
    (dissoc this :server))
  
  clojure.lang.IFn
  (invoke [this]
    (co/stop this)))

(defmethod rt/setup-runtime :http [conf _]
  ;; Return a function that when invoked, returns another function to shut down the server
  ;; TODO See if we can change this into a component
  (fn [rt]
    (log/debug "Starting http server with runtime:" rt)
    (-> (->HttpServer rt)
        (co/start))))

(defn on-server-close
  "Returns a deferred that resolves when the server shuts down."
  [server]
  (md/future (netty/wait-for-close (:server server))))
