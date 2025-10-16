(ns minio
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io])
  (:import [io.minio MinioClient PutObjectArgs]))

(defn make-s3-client [url access-key secret]
  (.. (MinioClient/builder)
      (endpoint url)
      (credentials access-key secret)
      (build)))

(defn put-object-args [bucket dest stream size]
  (.. (PutObjectArgs/builder)
      (bucket bucket)
      (object dest)
      (stream stream size -1)
      ;; Make file publicly available
      (headers {"x-amz-acl" "public-read"})
      (build)))

(defn put-s3-object
  "Uploads the file `f` to given bucket destination"
  [client bucket dest stream size]
  (with-open [s stream]
    (let [args (put-object-args bucket dest s size)]
      (.putObject client args))))

(defn put-s3-file
  "Uploads the file `f` to given bucket destination"
  [client bucket dest f]
  (put-s3-object client bucket dest (io/input-stream f) (fs/size f)))


