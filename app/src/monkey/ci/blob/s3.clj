(ns monkey.ci.blob.s3
  "Implementation of blob store that uses an Amazon S3-compatible bucket as backend"
  (:require [amazonica.aws.s3 :as s3]
            [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci.blob.common :as c]
            [monkey.ci.build.archive :as a]
            [monkey.ci
             [protocols :as p]
             [utils :as u]]))

(defrecord S3BlobStore [conf]
  p/BlobStore
  (save-blob [this src dest md]
    (let [tmp (c/tmp-archive conf)
          r (c/make-archive src tmp)
          details {:bucket-name (:bucket-name conf)
                   :key dest
                   :file (u/abs-path tmp)
                   :metadata md}]
      (log/debug "Uploading to bucket:" details)
      (-> (s3/put-object conf details)
          (md/chain (constantly r))
          (md/finally #(fs/delete tmp)))))
  
  (restore-blob [this src dest]
    (log/debug "Restoring blob from bucket" (:bucket-name conf) ", path" src "to" dest)
    (let [res (s3/get-object conf (:bucket-name conf) src)]
      (-> res
          (md/chain #(a/extract (:input-stream %) dest))
          (md/finally #(.close (:input-stream res))))))
  
  (get-blob-stream [this src])
  
  (put-blob-stream [this src dest])
  
  (get-blob-info [this src]))
