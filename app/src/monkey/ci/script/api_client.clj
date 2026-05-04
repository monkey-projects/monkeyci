(ns monkey.ci.script.api-client
  "Functions for invoking the build script API."
  (:require [aleph.http :as http]
            [aleph.http.client-middleware :as mw]
            [babashka.fs :as fs]
            [buddy.core.codecs :as bcc]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [medley.core :as mc]
            [monkey.ci
             [blob :as blob]
             [build :as b]
             [errors :as err]
             [protocols :as p]
             [utils :as u]]
            [monkey.ci.build.archive :as archive]
            [monkey.ci.events.http :as eh]
            [monkey.ci.web.crypto :as crypto]))

(defn as-edn [req]
  (-> req
      (assoc :accept :edn
             :as :clojure)))

(defn api-request
  "Sends a request to the api at configured url"
  [{:keys [url token]} req]
  (letfn [(build-request [req]
            (assoc req
                   :url (str url (:path req))
                   :oauth-token token
                   :middelware mw/default-middleware))
          (handle-error [ex]
            (throw (err/unwrap-exception ex)))]
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

(defrecord BuildApiArtifactRepository [client base-path]
  p/ArtifactRepository
  (restore-artifact [this _ id dest]
    (log/debug "Restoring artifact using build API:" id "to" dest)
    (u/log-deferred-elapsed
     (-> (client {:method :get
                  :path (str base-path id)
                  :as :stream})
         (md/chain
          :body
          #(archive/extract % dest))
         (md/catch (fn [ex]
                     (if (= 404 (:status (ex-data ex)))
                       (log/warn "Artifact not found:" id)
                       (throw ex)))))
     (str "Restored artifact from build API: " id)))

  (save-artifact [this _ id src]
    (let [tmp (fs/create-temp-file)
          ;; TODO Skip the tmp file intermediate step, it takes up disk space and is slower
          arch (try
                 (blob/make-archive src (fs/file tmp))
                 (catch Exception ex
                   (log/error "Unable to create archive from" src ex)
                   (throw ex)))
          stream (io/input-stream (fs/file tmp))]
      (log/debugf "Uploading artifact/cache to api server: %s from %s (compressed size: %.2f MB)"
                  id src (u/mb arch))
      (u/log-deferred-elapsed
       (-> (client (as-edn {:method :put
                            :path (str base-path id)
                            :body stream}))
           (md/chain
            :body
            (partial merge arch))
           (md/finally
             ;; Clean up
             (fn []
               (.close stream)
               (fs/delete tmp))))
       (str "Saved artifact to build API: " id)))))

(defn make-artifact-repository [client]
  (->BuildApiArtifactRepository client "/artifact/"))

(defn make-cache-repository
  "Creates an `ArtifactRepository` that can be used to upload/download caches"
  [client]
  (->BuildApiArtifactRepository client "/cache/"))
