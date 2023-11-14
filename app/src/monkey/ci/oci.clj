(ns monkey.ci.oci
  "Oracle cloud specific functionality"
  (:require [monkey.oci.os
             [martian :as os]
             [stream :as s]]))

(defn stream-to-bucket
  "Pipes an input stream to a bucket object using multipart uploads.
   Returns a deferred that will resolve when the upload completes.
   That is, when the input stream closes, or an error occurs."
  [conf ^java.io.InputStream in]
  (let [ctx (os/make-context conf)]
    (s/input-stream->multipart ctx (assoc conf :input-stream in))))
