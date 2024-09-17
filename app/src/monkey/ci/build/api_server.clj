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
            [buddy.core
             [codecs :as bcc]
             [nonce :as bcn]]
            [clj-commons.byte-streams :as bs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [artifacts :as art]
             [build :as build]
             [cache :as cache]
             [credits :as credits]
             [edn :as edn]
             [jobs :as j]
             [labels :as lbl]
             [runtime :as rt]
             [protocols :as p]
             [spec :as spec]
             [utils :as u]]
            ;; Very strange, but including this causes spec gen exceptions when using cloverage :confused:
            ;; [monkey.ci.spec.build]
            [monkey.ci.events.http :as eh]
            [monkey.ci.spec.api-server :as aspec]
            [monkey.ci.web
             [common :as c]
             [handler :as h]]
            [reitit.ring :as ring]
            [reitit.coercion.schema]
            [ring.util.response :as rur]
            [schema.core :as s]))

(defn generate-token
  "Generates a new API security token.  This token can be set in the API server
   and should be passed on to the build script."
  []
  (bcc/bytes->b64-str (bcn/random-bytes 40)))

(def context ::context)

(defn req->ctx
  "Gets the config map from the request"
  [req]
  (get-in req [:reitit.core/match :data context]))

(def req->build
  "Gets current build configuration from the request"
  (comp :build req->ctx))

(def req->events
  (comp :events req->ctx))

(def req->workspace
  (comp :workspace req->ctx))

(def req->artifacts
  (comp :artifacts req->ctx))

(def req->cache
  (comp :cache req->ctx))

(def req->containers
  (comp :containers req->ctx))

(def req->params
  (comp :params req->ctx))

(def repo-id
  (comp (juxt :customer-id :repo-id) req->build))

(def api-client (acmw/wrap-request #'http/request))

(defn- api-request
  "Sends a request to the global API according to configuration"
  [{:keys [url token]} req]
  (letfn [(handle-error [ex]
            (throw (ex-info
                    (ex-message ex)
                    (-> (ex-data ex)
                        ;; Read the response body in case of error
                        (mc/update-existing :body bs/to-string)))))]
    (-> (api-client (-> req
                        (assoc :url (str url (:path req))
                               :accept "application/edn"
                               :oauth-token token)
                        (dissoc :path)))
        (md/chain
         :body
         edn/edn->)
        (md/catch handle-error))))

#_(defn- params-from-storage
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
           (rur/response)))))

(defn get-params-from-api [api build]
  (api-request api {:path (format "/customer/%s/repo/%s/param" (:customer-id build) (:repo-id build))
                    :method :get}))

(defn- invalid-config [& _]
  (-> (rur/response {:error "Invalid or incomplete API context configuration"})
      (rur/status 500)))

(defn get-params [req]
  (rur/response @(p/get-build-params (req->params req))))

(defn get-all-ip-addresses
  "Lists all non-loopback, non-virtual site local ip addresses"
  []
  (->> (enumeration-seq (java.net.NetworkInterface/getNetworkInterfaces))
       (remove (some-fn (memfn isLoopback) (memfn isVirtual)))
       (mapcat (comp enumeration-seq (memfn getInetAddresses)))
       (filter (memfn isSiteLocalAddress))
       (map (memfn getHostAddress))))

(defn get-ip-addr
  "Determines the ip address of this VM"
  []
  (first (get-all-ip-addresses)))

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

(defn dispatch-events [req]
  (let [sid (build/sid (req->build req))]
    (log/info "Dispatching event stream to client for build" sid)
    (eh/event-stream (req->events req) {:sid sid})))

(defn- stream-response [s & [nil-code]]
  (log/debug "Sending stream to client:" s)
  (if s
    (-> (rur/response s)
        ;; TODO Return correct content type according to stream
        (rur/content-type "application/octet-stream"))
    (rur/status (or nil-code 404))))

(defn- download-stream [req store path nil-stream-code]
  ;; TODO Use local disk storage to avoid re-downloading it from persistent storage
  (let [stream (when (and store path)
                 (p/get-blob-stream store path))]
    (cond
      (not store) (invalid-config)
      (not stream) (rur/status nil-stream-code)
      :else (stream-response @stream nil-stream-code))))

(defn- upload-stream [{stream :body :as req} store path success-resp]
  (cond
    (some nil? [stream path]) (rur/status 400)
    (nil? store) (invalid-config)
    :else
    (try
      @(p/put-blob-stream store stream path)
      (rur/response success-resp)
      (finally
        (.close stream)))))

(defn download-workspace [req]
  (let [ws (req->workspace req)
        path (:workspace (req->build req))]
    (download-stream req ws path 204)))

(defn- with-artifact
  "Applies `f` to artifacts using values retrieved from request"
  [f]
  (fn [req]
    (let [store (req->artifacts req)
          id (get-in req [:parameters :path :artifact-id])
          path (when id (art/artifact-archive-path (req->build req) id))]
      (log/debug "Uploading artifact:" id ", storing it at path" path)
      (f req store path id))))

(def upload-artifact
  "Uploads a new artifact.  The body should contain the file contents."
  (with-artifact
    (fn [req store path id]
      (log/debug "Receiving artifact from client:" id "at path" path)
      (upload-stream req store path {:artifact-id id}))))

(def download-artifact
  "Downloads an artifact.  The body contains the artifact as a stream."
  (with-artifact
    (fn [req store path _]
      (log/debug "Sending artifact to client:" path)
      (download-stream req store path 404))))

(defn- with-cache
  "Applies `f` to cache using values retrieved from request"
  [f]
  (fn [req]
    (let [store (req->cache req)
          id (get-in req [:parameters :path :cache-id])
          path (when id (cache/cache-archive-path (req->build req) id))]
      (f req store path id))))

(def upload-cache
  (with-cache (fn [req store path id]
                (log/debug "Receiving cache from client:" id "at path" path)
                (upload-stream req store path {:cache-id id}))))

(def download-cache
  (with-cache (fn [req store path _]
                (log/debug "Sending cache to client:" path)
                (download-stream req store path 404))))

(defn start-container [req]
  (let [job (get-in req [:parameters :body :job])
        containers (req->containers req)
        fire-end-event (fn [res]
                         (log/debug "Container finished, dispatching :container-job/end event")
                         (p/post-events (req->events req)
                                        {:type :container-job/end
                                         :sid (build/sid (req->build req))
                                         :job-id (j/job-id job)
                                         :result res}))]
    ;; Start the container, don't wait for the result.  It's up to the client
    ;; to monitor job end event.
    (-> (p/run-container containers job)
        (md/chain fire-end-event))
    (-> (rur/response {:job (j/set-credit-multiplier job (credits/container-credit-multiplier containers job))})
        (rur/status 202))))

(def edn #{"application/edn"})

(def params-routes
  ["/params"
   {:get get-params
    :responses {200 {:body [h/ParameterValue]}}
    :produces edn}])

(def events-routes
  ["/events"
   {:post {:handler post-events
           :parameters {:body [{s/Keyword s/Any}]}
           :responses {202 {}}
           :consumes edn}
    :get  {:handler dispatch-events
           :response {200 {}}
           :produces "text/event-stream"}}])

(def workspace-routes
  ["/workspace"
   {:get download-workspace
    :responses {200 {}}}])

(def artifact-routes
  ["/artifact/:artifact-id"
   {:parameters {:path {:artifact-id s/Str}}
    :put {:handler upload-artifact
          :responses {200 {}}
          :produces edn
          :parameters {:body s/Any}}
    :get {:handler download-artifact
          :responses {200 {}}}}])

(def cache-routes
  ["/cache/:cache-id"
   {:parameters {:path {:cache-id s/Str}}
    :put {:handler upload-cache
          :responses {200 {}}
          :produces edn}
    :get {:handler download-cache
          :responses {200 {}}}}])

(def container-routes
  ["/container"
   {:post {:handler start-container
           :responses {202 {}}
           :produces edn
           :parameters {:body s/Any}}}])

(def routes [""
             [["/test"
               {:get (constantly (rur/response {:result "ok"}))
                :responses {200 {:body {:result s/Str}}}
                :produces edn}]
              params-routes
              events-routes
              workspace-routes
              artifact-routes
              cache-routes
              container-routes
              ;; TODO Log uploads
              ]])

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
  [{:keys [port token] :or {port 0} :as conf}]
  {:pre [(spec/valid? ::aspec/config conf)]}
  (log/debug "Starting API server at ip address" (get-ip-addr) "and port" port)
  (let [token (or token (generate-token))
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
       (merge (select-keys rt [:events :artifacts :cache :workspace :containers]))
       (mc/filter-vals some?)))

(defn with-build [conf b]
  (assoc conf :build b))

(defn srv->api-config
  "Creates a configuration object that can be passed to build runners, that includes the url"
  [{:keys [port] :as conf}]
  (-> conf
      (select-keys [:port :token])
      (assoc :url (format "http://%s:%d" (get-ip-addr) port))))
