(ns monkey.ci.blob.minio
  "MinIO wrapper code to access S3 buckets.  These functions mainly delegate to
   the appropriate Minio classes using the async client."
  (:require [manifold.deferred :as md])
  (:import [io.minio MinioAsyncClient Result StatObjectResponse
            ListObjectsArgs
            GetObjectArgs
            PutObjectArgs
            StatObjectArgs]
           [io.minio.messages Item]))

(defn make-client [url access-key secret]
  (.. (MinioAsyncClient/builder)
      (endpoint url)
      (credentials access-key secret)
      (build)))

(defn- item->map [^Item item]
  {:name (.objectName item)
   :last-modified (.lastModified item)
   :size (.size item)
   :metadata (.userMetadata item)
   :dir? (.isDir item)})

(defn list-objects
  "Recursively lists objects in the bucket with given prefix"
  [client bucket prefix]
  (md/chain
   (->> (.. (ListObjectsArgs/builder)
            (bucket bucket)
            (prefix prefix)
            (recursive true)
            (build))
        (.listObjects client))
   (partial map (comp item->map (memfn ^Result get)))))

(defn get-object
  "Returns a future of an `InputStream` of the given object.  Don't forget to close it
   after use."
  [client bucket path]
  (->> (.. (GetObjectArgs/builder)
           (bucket bucket)
           (object path)
           (build))
       (.getObject client)))

(defn- headers->map [h]
  (->> h
       (map (fn [p]
              [(.getFirst p) (.getSecond p)]))
       (into {})))

(defn- put-result->map [r]
  {:bucket (.bucket r)
   :object (.object r)
   :headers (headers->map (.headers r))
   :version (.versionId r)})

(defn put-object
  "Uploads stream to the specified destination.  Optional content type and
   metadata can be specified.  Specify `size` if the stream size is known.
   You can also specify a `file` instead of a `stream`."
  [client bucket path {:keys [stream size content-type metadata file]}]
  (md/chain
   (->> (cond-> (.. (PutObjectArgs/builder)
                    (bucket bucket)
                    (object path))
          stream (.stream stream (or size -1) (if size -1 (* 10 1024 1024)))
          file (.filename file)
          metadata (.userMetadata metadata)
          content-type (.contentType content-type)
          true (.build))
        (.putObject client))
   (put-result->map)))

(defn- stats->map [^StatObjectResponse resp]
  {:name (.object resp)
   :bucket (.bucket resp)
   :last-modified (.lastModified resp)
   :size (.size resp)
   :metadata (.userMetadata resp)
   :content-type (-> (.headers resp) (.get "content-type"))})

(defn get-object-details
  [client bucket path]
  (md/chain
   (->> (.. (StatObjectArgs/builder)
            (bucket bucket)
            (object path)
            (build))
        (.statObject client))
   stats->map))

(defn object-exists? [client bucket path]
  (md/chain
   (list-objects client bucket path)
   not-empty))
