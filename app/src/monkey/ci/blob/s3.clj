(ns monkey.ci.blob.s3
  "Implementation of blob store that uses an Amazon S3-compatible bucket as backend"
  (:require [amazonica.aws.s3 :as s3]
            [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [java-time.api :as jt]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci.blob.common :as c]
            [monkey.ci.build.archive :as a]
            [monkey.ci
             [protocols :as p]
             [utils :as u]]))

;; Configuration contains all settings needed to connect to the bucket endpoint,
;; including bucket-name.
(defrecord S3BlobStore [conf]
  p/BlobStore
  (save-blob [this src dest md]
    ;; We could also drop the intermediate file and use piped streams instead, which
    ;; would be a bit faster and would not require disk space.  But then we'd need
    ;; an additional thread.
    (let [tmp (c/tmp-archive conf)
          r (c/make-archive src tmp)
          details {:bucket-name (:bucket-name conf)
                   :key dest
                   :file (u/abs-path tmp)
                   :metadata md}]
      (log/debug "Uploading to bucket:" details)
      (-> (md/future (s3/put-object conf details))
          (md/chain (constantly r))
          (md/finally #(fs/delete tmp)))))
  
  (restore-blob [this src dest]
    (log/debug "Restoring blob from bucket" (:bucket-name conf) ", path" src "to" dest)
    (let [res (md/future (s3/get-object conf (:bucket-name conf) src))]
      (-> res
          (md/chain #(a/extract (:input-stream %) dest))
          (md/finally #(.close (:input-stream @res))))))
  
  (get-blob-stream [this src]
    (log/debug "Downloading stream from bucket" (:bucket-name conf) "at" src)
    (-> (s3/get-object conf (:bucket-name conf) src)
        (md/future)
        (md/chain :input-stream)))
  
  (put-blob-stream [this src dest]
    (let [details {:bucket-name (:bucket-name conf)
                   :key dest
                   :input-stream src}]
      (log/debug "Uploading stream to bucket:" details)
      (-> (s3/put-object conf details)
          (md/future)
          (md/chain (fn [_]
                      {:src src
                       :dest dest})))))
  
  (get-blob-info [this src]
    (-> (s3/get-object-metadata conf {:bucket-name (:bucket-name conf) :key src})
        (md/future)
        (md/chain (fn [res]
                    (-> {:src src
                         :size (:content-length res)
                         :metadata (:user-metadata res)
                         :result res}
                        (merge (select-keys res [:last-modified :content-type]))
                        (mc/update-existing :last-modified jt/instant)))))))
