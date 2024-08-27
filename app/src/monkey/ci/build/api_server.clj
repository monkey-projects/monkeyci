(ns monkey.ci.build.api-server
  "Functions for setting up a build script API.  The build runner starts its own
   API server, which is only accessible by the build script and any containers it
   starts.  This is for security reasons, since the build script is untrusted code.
   Restricting access to the API server is done on infra level, by setting up network
   security rules.  Piping all traffic through the build runner also ensures that
   the global API will not be overloaded by malfunctioning (or misbehaving) builds."
  (:require [aleph
             [http :as http]
             [netty :as an]]            
            [aleph.http
             [client-middleware :as acmw]
             [multipart :as mp]]
            [clj-commons.byte-streams :as bs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [martian.interceptors :as mi]
            [medley.core :as mc]
            [monkey.ci
             [labels :as lbl]
             [runtime :as rt]
             [protocols :as p]
             [spec :as spec]
             [storage :as st]]
            ;; Very strange, but including this causes spec gen exceptions when using cloverage :confused:
            ;; [monkey.ci.spec.build]
            [monkey.ci.spec.api-server :as aspec]
            [monkey.ci.web.common :as c]
            [reitit
             [ring :as ring]
             [swagger :as swagger]]
            [reitit.coercion.schema]
            [ring.util.response :as rur]
            [schema.core :as s]))

(defn generate-token
  "Generates a new API security token.  This token can be set in the API server
   and should be passed on to the build script."
  []
  (str (random-uuid)))

(def context ::context)

(defn req->ctx
  "Gets the config map from the request"
  [req]
  (get-in req [:reitit.core/match :data context]))

(def req->build
  "Gets current build configuration from the request"
  (comp :build req->ctx))

(def req->storage
  (comp :storage req->ctx))

(def req->api
  (comp :api req->ctx))

(def req->workspace
  (comp :workspace req->ctx))

(def req->artifacts
  (comp :artifacts req->ctx))

(def repo-id
  (comp (juxt :customer-id :repo-id) req->build))

(def api-client (acmw/wrap-request #'http/request))

(defn- api-request
  "Sends a request to the global API according to configuration"
  [{:keys [url token]} req]
  (api-client (-> req
                  (assoc :url (str url (:path req))
                         :accept "application/edn"
                         :oauth-token token
                         :as :clojure)
                  (dissoc :path))))

(defn- params-from-storage
  "Fetches parameters from storage, using the current build configuration.
   Returns a deferred, or `nil` if there is no storage configuration in the
   context."
  [req]
  (when-let [st (req->storage req)]
    (let [build (req->build req)
          params (st/find-params st (:customer-id build))
          repo (st/find-repo st (repo-id req))]
      (->> params
           (lbl/filter-by-label repo)
           (mapcat :parameters)
           (rur/response)
           (md/success-deferred)))))

(defn- params-from-api
  "Sends a request to the global api to retrieve build parameters."
  [req]
  (when-let [api (req->api req)]
    (let [build (req->build req)]
      (api-request api {:path (format "/customer/%s/repo/%s/param" (:customer-id build) (:repo-id build))
                        :method :get}))))

(defn- invalid-config [_]
  (md/error-deferred (ex-info "Invalid or incomplete API context configuration"
                              {:status 500})))

(def get-params (some-fn params-from-storage
                         params-from-api
                         invalid-config))

(defn get-ip-addr
  "Determines the ip address of this VM"
  []
  ;; TODO There could be more than one
  (.. (java.net.Inet4Address/getLocalHost) (getHostAddress)))

(defn post-events [req]
  (let [evt (get-in req [:parameters :body])]
    (log/debug "Received events from build script:" evt)
    (try 
      {:status (if (rt/post-events (req->ctx req) evt)
                 202
                 500)}
      (catch Exception ex
        (log/error "Unable to dispatch event" ex)
        {:status 500}))))

(defn download-workspace [req]
  (let [ws (req->workspace req)
        path (:workspace (req->build req))
        stream (when (and ws path)
                 (p/get-blob-stream ws path))]
    (cond
      (not ws) (-> (rur/response {:message "Workspace not configured"})
                   (rur/status 500))
      (not stream) (rur/status 204)
      :else (md/chain
             stream
             rur/response
             ;; TODO Return correct content type according to stream
             #(rur/content-type % "application/octet-stream")))))

(defn upload-artifact
  "Uploads a new artifact.  The body should be a multipart request that contains the
   contents."
  [req]
  (let [store (req->artifacts req)
        id (get-in req [:parameters :path :artifact-id])
        stream (:body req)]
    (log/info "Received uploaded artifact:" id)
    (log/debug "Content type:" (get-in req [:headers "content-type"]))
    (if stream
      (-> (md/chain
           (p/put-blob-stream store stream id)
           (constantly (rur/response (pr-str {:artifact-id id}))))
          (md/finally
            #(.close stream)))
      (rur/status 400))))

(defn download-artifact
  "Downloads an artifact.  The body contains the artifact as a stream."
  [req]
  ;; TODO Use local disk storage to avoid re-downloading it from persistent storage
  (rur/response "TODO"))

(def edn #{"application/edn"})

(def routes ["" {:swagger {:id :monkeyci/build-api}}
             [["/swagger.json"
               {:no-doc true
                :get (swagger/create-swagger-handler)}]
              ["/test"
               {:get (constantly (rur/response {:result "ok"}))
                :summary "Test endpoint"
                :operationId :test
                :responses {200 {:body {:result s/Str}}}
                :produces edn}]
              ["/params"
               {:get get-params
                :summary "Retrieve configured build parameters"
                :operationId :get-params
                :responses {200 {:body {s/Str s/Str}}}
                :produces edn}]
              ["/events"
               {:post post-events
                :summary "Post a events to the bus"
                :operationId :post-events
                :parameters {:body [{s/Keyword s/Any}]}
                :responses {202 {}}
                :consumes edn}]
              ["/workspace"
               {:get download-workspace
                :summary "Downloads workspace for the build"
                :operationId :download-workspace
                :responses {200 {}}}]
              ["/artifact/:artifact-id"
               {:parameters {:path {:artifact-id s/Str}}
                :put {:handler #'upload-artifact
                      :summary "Uploads a new artifact"
                      :operationId :upload-artifact
                      :responses {200 {}}
                      :produces edn}
                :get {:handler download-artifact
                      :summary "Downloads an artifact"
                      :operationId :download-artifact
                      :responses {200 {}}}}]]])

(defn security-middleware
  "Middleware that checks if the authorization header matches the specified token"
  [handler token]
  (fn [req]
    (let [auth (get-in req [:headers "authorization"])]
      (if (= auth (str "Bearer " token))
        (handler req)
        (rur/status 401)))))

(defn make-router
  ([opts routes]
   (ring/router
    routes
    {:data {:middleware (concat [[security-middleware (:token opts)]]
                                c/default-middleware)
            :muuntaja (c/make-muuntaja)
            :coercion reitit.coercion.schema/coercion
            context opts}}))
  ([opts]
   (make-router opts routes)))

(defn make-app [opts]
  {:pre [(spec/valid? ::aspec/app-config opts)]}
  (c/make-app (make-router opts)))

(defn start-server
  "Starts a build API server with a randomly generated token.  Returns the server
   and token."
  [{:keys [port] :or {port 0} :as conf}]
  {:pre [(spec/valid? ::aspec/config conf)]}
  (log/debug "Starting API server at ip address" (get-ip-addr) "with config" conf)
  (let [token (generate-token)
        srv (http/start-server
             (make-app (assoc conf :token token))
             {:port port})]
    {:server srv
     :port (an/port srv)
     :token token}))

(defn rt->api-server-config
  "Creates a config map for the api server from the given runtime"
  [rt]
  (->> {:port (rt/runner-api-port rt)}
       (merge (select-keys rt [:events :artifacts :cache :workspace :storage]))
       (mc/filter-vals some?)))

(defn with-build [conf b]
  (assoc conf :build b))
