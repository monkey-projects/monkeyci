(ns monkey.ci.blob.s3
  "Implementation of blob store that uses an Amazon S3-compatible bucket as backend"
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [java-time.api :as jt]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci.blob
             [common :as c]
             [minio :as minio]]
            [monkey.ci.build.archive :as a]
            [monkey.ci
             [protocols :as p]
             [utils :as u]]))

(defn- with-prefix [dest {:keys [prefix]}]
  (cond->> dest
    (some? prefix) (str prefix)))

(defn- conf->client [conf]
  (dissoc conf :bucket-name :type :prefix))

(defn- object-exists?
  "Asynchronously checks if object exists"
  [client conf path]
  (minio/object-exists? client (:bucket-name conf) path))

(defn make-client [{:keys [endpoint access-key secret-key]}]
  (minio/make-client endpoint access-key secret-key))

;; Configuration contains all settings needed to connect to the bucket endpoint,
;; including bucket-name.
(defrecord S3BlobStore [client conf]
  p/BlobStore
  (save-blob [this src dest md]
    ;; We could also drop the intermediate file and use piped streams instead, which
    ;; would be a bit faster and would not require disk space.  But then we'd need
    ;; an additional thread.
    (let [tmp (c/tmp-archive conf)
          r (c/make-archive src tmp)
          bucket (:bucket-name conf)
          details (-> {:file (u/abs-path tmp)}
                      (mc/assoc-some :metadata md))]
      (log/debug "Uploading to bucket" bucket ":" details)
      (-> (minio/put-object client bucket (with-prefix dest conf) details)
          (md/chain (constantly r))
          (md/finally #(fs/delete tmp)))))
  
  (restore-blob [this src dest]
    (let [src (with-prefix src conf)]
      (log/debug "Restoring blob from bucket" (:bucket-name conf) ", path" src "to" dest)
      (md/chain
       (object-exists? client conf src)
       (fn [exists?]
         (when exists?
           (let [res (minio/get-object client (:bucket-name conf) src)]
             (-> res
                 (md/chain #(a/extract % dest))
                 (md/finally #(.close @res)))))))))
  
  (get-blob-stream [this src]
    (let [src (with-prefix src conf)]
      (log/debug "Downloading stream from bucket" (:bucket-name conf) "at" src)
      (md/chain
       (object-exists? client conf src)
       (fn [exists?]
         (when exists?
           (minio/get-object client (:bucket-name conf) src))))))
  
  (put-blob-stream [this src dest]
    (let [dest (with-prefix dest conf)
          details {:stream src}]
      (log/debug "Uploading stream to bucket:" dest)
      (md/chain
       (minio/put-object client (:bucket-name conf) dest details)
       (fn [_]
         {:src src
          :dest dest}))))
  
  (get-blob-info [this src]
    (let [src (with-prefix src conf)]
      (md/chain
       (object-exists? client conf src)
       (fn [exists?]
         (when exists?
           (minio/get-object-details client (:bucket-name conf) src)))
       (fn [res]
         (when res
           (-> (select-keys res [:size :metadata :last-modified :content-type])
               (assoc :src src
                      :result res)
               (mc/update-existing :last-modified jt/instant))))))))
