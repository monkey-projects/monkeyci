(ns monkey.ci.build.api
  "Functions for invoking the build script API."
  (:require [aleph.http :as http]
            [aleph.http.client-middleware :as mw]
            [buddy.core.codecs :as bcc]
            [clj-commons.byte-streams :as bs]
            [clojure.tools.logging :as log]
            [manifold
             [bus :as bus]
             [deferred :as md]
             [stream :as ms]]
            [medley.core :as mc]
            [monkey.ci.build :as b]
            [monkey.ci.events.http :as eh]
            [monkey.ci.web.crypto :as crypto]))

(defn as-edn [req]
  (-> req
      (assoc :accept :edn
             :as :clojure)))

(def api-middleware
  (conj mw/default-middleware
        mw/handle-error-status))

(defn api-request
  "Sends a request to the api at configured url"
  [{:keys [url token]} req]
  (letfn [(build-request [req]
            (assoc req
                   :url (str url (:path req))
                   :oauth-token token
                   :middelware api-middleware))
          (handle-error [ex]
            (throw (ex-info
                    (ex-message ex)
                    (-> (ex-data ex)
                        ;; Read the response body in case of error
                        (mc/update-existing :body bs/to-string)))))]
    (-> req
        (build-request)
        (http/request)
        (md/catch handle-error))))

(defn make-client
  "Creates a new api client function for the given url.  It returns a function
   that requires a request object that will send a http request.  The function 
   returns a deferred with the result body.  An authentication token is required."
  [url token]
  (partial api-request {:url url :token token}))

(def ctx->api-client (comp :client :api))
(def ^:deprecated rt->api-client ctx->api-client)

(def decrypt-key
  "Given an encrypted data encryption key, decrypts it by sending a decryption
   request to the build api server."
  ;; TODO Smarter caching
  (memoize
   (fn 
     [client enc-dek]
     (let [p (pr-str enc-dek)]
       @(md/chain
         (client {:path "/decrypt-key"
                  :request-method :post
                  :body p
                  :headers {:content-type "application/edn"
                            :content-length (str (count p))}})
         :body
         slurp)))))

(defn- fetch-params* [ctx]
  (let [client (ctx->api-client ctx)]
    (log/debug "Fetching repo params for" (-> ctx :build b/sid))
    (->> @(client (as-edn {:path "/params"
                           :method :get}))
         :body
         (map (juxt :name :value))
         (into {}))))

(defn- decrypt-extra-params
  "Decrypts any additional parameters that have been specified on the build."
  [{:keys [build] :as ctx}]
  (let [p (:params build)]
    (when (not-empty p)
      (let [dek (-> (decrypt-key (ctx->api-client ctx) (:dek build))
                    (bcc/b64->bytes))
            iv (crypto/cuid->iv (:org-id build))]
        (mc/map-vals (partial crypto/decrypt dek iv) p)))))

(defn- fetch-with-extra-params [ctx]
    ;; Augment the fetched params with any additional parameters
    ;; that have been specified on the build itself.
  (-> (fetch-params* ctx)
      (merge (decrypt-extra-params ctx))))

(def build-params
  "Retrieves the params for this build.  This fetches the parameters from the
   API, and adds to them any additional parameters that have been specified on
   the build itself.  Since these are in encrypted form, we need to decrypt
   them here."
  ;; Use memoize because we'll only want to fetch them once
  (memoize fetch-with-extra-params))

(defn download-artifact
  "Downloads the artifact with given id for the current job.  Returns an input
   stream that can then be written to disk, or unzipped using archive functions."
  [ctx id]
  (let [client (ctx->api-client ctx)]
    (log/debug "Downloading artifact for build" (-> ctx :build b/sid) ":" id)
    (-> @(client {:path (str "/artifact/" id)
                  :method :get})
        :body)))

(defn check-http-error [msg {:keys [status body] :as resp}]
  (if (or (nil? status) (>= status 400))
    (throw (ex-info msg
                    {:cause resp}))
    body))

(defn events-to-stream
  "Listens to events on the build api server.  This opens a streaming request
   that reads SSE from the server, and puts them on a manifold stream, which
   is returned, along with a function that can be used to close the stream."
  [client]
  (let [evt-stream (promise)
        src (-> (client {:request-method :get
                         :path "/events"
                         ;; Use custom connection pool for events because connections aren't given back
                         ;; to the pool, which results in requests blocking.
                         :pool (http/connection-pool {:total-connections 10})})
                (md/chain
                 (partial check-http-error "Failed to listen to build API events")
                 (fn [is]
                   ;; Store it so we can close it later
                   (deliver evt-stream is)
                   is)
                 bs/to-line-seq
                 ms/->source
                 (partial ms/filter not-empty)
                 (partial ms/map eh/parse-event-line))
                (deref))]
    ;; Return the source and a fn that can close the input stream.  We can't
    ;; rely on the on-drained handler from manifold because it's never called
    ;; when you close the source.
    [src
     (fn []
       ;; FIXME It seems that the connection to the server is never closed, even
       ;; when closing the stream body.  We should find a way to let aleph know it
       ;; can close the http connection.
       (log/debug "Closing event stream on client side")
       (if (realized? evt-stream)
         (.close @evt-stream)
         (log/warn "Unable to close event inputstream, not delivered yet."))
       (log/debug "Stream closed"))]))

(defn ^:deprecated event-bus
  "Creates an event bus that receives all events from the build api using the
   `/events` endpoint.  The bus can be subscribed to using `manifold.bus/subscribe`.
   Returns both the bus and a function which, when invoked, should close the http 
   connection."
  [client]
  (let [eb (bus/event-bus)
        [src close-fn] (events-to-stream client)]
    (ms/consume (fn [evt]
                  (log/debug "Got event from build API:" evt)
                  @(bus/publish! eb (:type evt) evt))
                src)
    {:bus eb
     :close close-fn}))

