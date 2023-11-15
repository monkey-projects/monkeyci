(ns monkey.ci.oci
  "Oracle cloud specific functionality"
  (:require [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci.utils :as u]
            [monkey.oci.os
             [martian :as os]
             [stream :as s]]))

(defn ->oci-config
  "Given a configuration map with credentials, turns it into a config map
   that can be passed to OCI context creators."
  [{:keys [credentials] :as conf}]
  (-> conf
      (merge (mc/update-existing credentials :private-key u/load-privkey))
      (dissoc :credentials)))

(defn ctx->oci-config
  "Gets the oci configuration for the given key from the context.  This merges
   in the general OCI configurationn."
  [ctx k]
  (u/deep-merge (:oci ctx)
                (k ctx)))

(defn stream-to-bucket
  "Pipes an input stream to a bucket object using multipart uploads.
   Returns a deferred that will resolve when the upload completes.
   That is, when the input stream closes, or an error occurs."
  [conf ^java.io.InputStream in]
  (log/debug "Piping stream to bucket using config" conf)
  (let [ctx (-> conf
                (->oci-config)
                (os/make-context))]
    (s/input-stream->multipart ctx (assoc conf :input-stream in))))
