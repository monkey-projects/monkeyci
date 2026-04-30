(ns monkey.ci.cli.server
  "Build API server created by the CLI when running builds locally.  The build
   script connects to it in order to store artifacts, fetch params, post events,
   and stream events back via SSE.

   This is a GraalVM-native-compatible reimplementation of
   monkey.ci.build.api-server from app/.  Key differences:
     - Uses http-kit instead of aleph (no Netty)
     - Uses core.async channels instead of manifold streams
     - Uses java.security.SecureRandom for token generation instead of buddy
     - Uses plain Ring routing instead of reitit+schema+muuntaja"
  (:require [clojure.core.async :as ca]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as http]
            [ring.util.response :as rur])
  (:import [java.security SecureRandom]
           [java.util Base64]))

;;;; Token generation

(defn generate-token
  "Generates a random 40-byte bearer token, encoded as base64.
   Equivalent to buddy.core.nonce/random-bytes -> bcc/bytes->b64-str but
   uses only java.security so it is GraalVM-native-safe."
  []
  (let [bytes (byte-array 40)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (Base64/getEncoder) bytes)))

;;;; SSE helpers

(def ^:private sse-prefix "data: ")

(defn- ->sse [evt]
  (str sse-prefix (pr-str evt) "\n\n"))

(defn- keepalive-evt []
  (->sse {:type :ping}))

;;;; Auth

(defn- authorized? [req token]
  (= (str "Bearer " token)
     (get-in req [:headers "authorization"])))

(defn- unauthorized []
  {:status 401
   :headers {"content-type" "text/plain"}
   :body "Unauthorized"})

;;;; Path helpers

(defn- artifact-path
  "Returns the File for a given artifact id inside the artifacts base dir."
  [artifact-dir build-sid artifact-id]
  (io/file artifact-dir (str/join "/" build-sid) artifact-id))

(defn- cache-path
  "Returns the File for a given cache id inside the cache base dir."
  [cache-dir build-sid cache-id]
  (io/file cache-dir (str/join "/" build-sid) cache-id))

;;;; Handlers

(defn- handle-test [_req]
  (-> (rur/response {:result "ok"})
      (rur/content-type "application/edn")
      (update :body pr-str)))

(defn- handle-get-params [_req {:keys [params]}]
  (-> (rur/response params)
      (rur/content-type "application/edn")
      (update :body pr-str)))

(defn- handle-post-events [req {:keys [event-mult-ch]}]
  (try
    (let [body (-> req :body slurp edn/read-string)]
      (log/debug "Received events from build script:" body)
      (ca/put! event-mult-ch body)
      {:status 202})
    (catch Exception ex
      (log/error "Unable to dispatch events" ex)
      {:status 500})))

(defn- handle-get-events [req {:keys [event-mult-ch build]}]
  (let [sid (:sid build)
        ;; Each SSE client gets its own tap channel
        tap-ch (ca/chan (ca/sliding-buffer 20))]
    (ca/tap (ca/mult event-mult-ch) tap-ch)
    (http/as-channel
     req
     {:on-open
      (fn [ch]
        (log/info "SSE client connected for build" sid)
        ;; Send an immediate keepalive so the client sees the connection open
        (http/send! ch (keepalive-evt) false)
        ;; Pipe events from the tap channel to the SSE client
        (ca/go-loop []
          (if-let [evt (ca/<! tap-ch)]
            (do
              (when (or (nil? sid) (= sid (:sid evt)))
                (log/trace "Sending event to SSE client:" (:type evt))
                (http/send! ch (->sse evt) false))
              (recur))
            ;; Channel closed — close SSE connection
            (do
              (log/debug "Event channel closed, closing SSE client")
              (http/send! ch "" true)))))
      :on-close
      (fn [_ch _status]
        (log/debug "SSE client disconnected for build" sid)
        (ca/untap (ca/mult event-mult-ch) tap-ch)
        (ca/close! tap-ch))})))

(defn- handle-download-workspace [_req {:keys [workspace-file]}]
  (if workspace-file
    (let [f (io/file workspace-file)]
      (if (.exists f)
        (-> (rur/response f)
            (rur/content-type "application/octet-stream"))
        (rur/not-found "Workspace not found")))
    {:status 204}))

(defn- handle-upload-artifact [req {:keys [artifact-dir build]}]
  (let [id     (-> req :path-params :artifact-id)
        path   (artifact-path artifact-dir (:sid build) id)]
    (if (some nil? [id artifact-dir])
      {:status 400 :body "Missing artifact id or store"}
      (try
        (io/make-parents path)
        (io/copy (:body req) path)
        (-> (rur/response {:artifact-id id})
            (rur/content-type "application/edn")
            (update :body pr-str))
        (catch Exception ex
          (log/error "Error uploading artifact" id ex)
          {:status 500})
        (finally
          (when-let [s (:body req)]
            (when (instance? java.io.InputStream s)
              (.close ^java.io.InputStream s))))))))

(defn- handle-download-artifact [req {:keys [artifact-dir build]}]
  (let [id   (-> req :path-params :artifact-id)
        path (artifact-path artifact-dir (:sid build) id)
        f    (io/file path)]
    (if (.exists f)
      (-> (rur/response f)
          (rur/content-type "application/octet-stream"))
      {:status 404})))

(defn- handle-upload-cache [req {:keys [cache-dir build]}]
  (let [id   (-> req :path-params :cache-id)
        path (cache-path cache-dir (:sid build) id)]
    (if (some nil? [id cache-dir])
      {:status 400 :body "Missing cache id or store"}
      (try
        (io/make-parents path)
        (io/copy (:body req) path)
        (-> (rur/response {:cache-id id})
            (rur/content-type "application/edn")
            (update :body pr-str))
        (catch Exception ex
          (log/error "Error uploading cache" id ex)
          {:status 500})
        (finally
          (when-let [s (:body req)]
            (when (instance? java.io.InputStream s)
              (.close ^java.io.InputStream s))))))))

(defn- handle-download-cache [req {:keys [cache-dir build]}]
  (let [id   (-> req :path-params :cache-id)
        path (cache-path cache-dir (:sid build) id)
        f    (io/file path)]
    (if (.exists f)
      (-> (rur/response f)
          (rur/content-type "application/octet-stream"))
      {:status 404})))

(defn- handle-decrypt-key [req {:keys [key-decrypter build]}]
  ;; For local builds the DEK is passed through as-is (no vault encryption).
  ;; A custom key-decrypter fn can be injected for testing or future use.
  ;; The decrypter receives [build encrypted-key] and returns the decrypted key.
  (let [body (-> req :body slurp edn/read-string)
        decrypter (or key-decrypter (fn [_build k] k))]
    (try
      (-> (rur/response (decrypter build body))
          (rur/content-type "application/edn")
          (update :body pr-str))
      (catch Exception ex
        (log/error "Error decrypting key" ex)
        {:status 500}))))

;;;; Path-param extraction
;;
;; http-kit does not parse path params — we do it with a simple prefix-strip.

(defn- strip-prefix [uri prefix]
  (when (str/starts-with? uri prefix)
    (subs uri (count prefix))))

;;;; Router

(defn make-handler
  "Builds the Ring handler for the build API server.

   `ctx` is a map with:
     :token          — bearer token string (required)
     :build          — build map with at least :sid (optional, [:org-id :repo-id :build-id])
     :params         — seq of {:name … :value …} maps (optional)
     :artifact-dir   — java.io.File or String for artifact storage (optional)
     :cache-dir      — java.io.File or String for cache storage (optional)
     :workspace-file — java.io.File or String for the workspace archive (optional)
     :event-mult-ch  — core.async mult-capable channel for events (required for SSE)
     :key-decrypter  — fn of [build encrypted-key] -> key (optional)"
  [{:keys [token] :as ctx}]
  (fn [req]
    (if-not (authorized? req token)
      (unauthorized)
      (let [method (:request-method req)
            uri    (:uri req)]
        (cond
          ;; GET /test
          (and (= method :get) (= uri "/test"))
          (handle-test req)

          ;; GET /params
          (and (= method :get) (= uri "/params"))
          (handle-get-params req ctx)

          ;; POST /events
          (and (= method :post) (= uri "/events"))
          (handle-post-events req ctx)

          ;; GET /events  — SSE stream
          (and (= method :get) (= uri "/events"))
          (handle-get-events req ctx)

          ;; GET /workspace
          (and (= method :get) (= uri "/workspace"))
          (handle-download-workspace req ctx)

          ;; PUT /artifact/:id
          (and (= method :put) (strip-prefix uri "/artifact/"))
          (handle-upload-artifact
           (assoc req :path-params {:artifact-id (strip-prefix uri "/artifact/")})
           ctx)

          ;; GET /artifact/:id
          (and (= method :get) (strip-prefix uri "/artifact/"))
          (handle-download-artifact
           (assoc req :path-params {:artifact-id (strip-prefix uri "/artifact/")})
           ctx)

          ;; PUT /cache/:id
          (and (= method :put) (strip-prefix uri "/cache/"))
          (handle-upload-cache
           (assoc req :path-params {:cache-id (strip-prefix uri "/cache/")})
           ctx)

          ;; GET /cache/:id
          (and (= method :get) (strip-prefix uri "/cache/"))
          (handle-download-cache
           (assoc req :path-params {:cache-id (strip-prefix uri "/cache/")})
           ctx)

          ;; POST /decrypt-key
          (and (= method :post) (= uri "/decrypt-key"))
          (handle-decrypt-key req ctx)

          :else
          {:status 404 :body "Not found"})))))

;;;; Lifecycle

(defn start-server
  "Starts the build API server.

   Options (all optional unless noted):
     :port          — port to listen on (default 0 = OS-assigned)
     :token         — bearer token; generated if not supplied
     :build         — build map
     :params        — user-specified build params map
     :artifact-dir  — directory for artifact storage
     :cache-dir     — directory for cache storage
     :workspace-file — path to the workspace archive
     :key-decrypter — fn [build enc-key] -> key

   Returns a map with:
     :server        — http-kit server object
     :port          — actual bound port
     :token         — bearer token (generated or supplied)
     :event-mult-ch — the upstream channel; put events here to broadcast to SSE clients"
  [{:keys [port token] :or {port 0} :as opts}]
  (let [token        (or token (generate-token))
        ;; Single upstream channel; broadcast via mult so multiple SSE
        ;; clients each get their own tap.
        event-ch     (ca/chan (ca/sliding-buffer 100))
        event-mult   (ca/mult event-ch)
        ctx          (assoc opts
                            :token token
                            :event-mult-ch event-ch)
        handler      (make-handler ctx)
        srv          (http/run-server handler {:port port
                                              :legacy-return-value? false})]
    (log/debug "Build API server started on port" (http/server-port srv))
    {:server        srv
     :port          (http/server-port srv)
     :token         token
     :event-mult-ch event-ch
     :event-mult    event-mult}))

(defn stop-server
  "Stops the build API server and closes the event channel."
  [{:keys [server event-mult-ch]}]
  (when server
    (log/debug "Stopping build API server")
    (http/server-stop! server))
  (when event-mult-ch
    (ca/close! event-mult-ch)))

(defn server->url
  "Returns the localhost URL for a started server map."
  [{:keys [port]}]
  (str "http://localhost:" port))
