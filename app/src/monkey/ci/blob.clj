(ns monkey.ci.blob
  "Blob storage functionality, used to store and restore large files
   or entire directories."
  (:require [monkey.ci
             [protocols :as p]
             [utils :as u]]
            [monkey.ci.blob
             [common :as bc]
             [disk :as bd]
             [s3 :as bs3]]
            [monkey.ci.oci.blob :as oci]))

(defn save
  "Saves blob from src to dest, with optional metadata"
  [blob src dest & [md]]
  (p/save-blob blob src dest md))

(def restore p/restore-blob)
(def input-stream p/get-blob-stream)
(def make-archive bc/make-archive)

(defmulti make-blob-store (fn [conf k]
                            (get-in conf [k :type])))

(def blob-store? p/blob-store?)

(defmethod make-blob-store :disk [conf k]
  ;; Make storage dir relative to the work dir
  (bd/->DiskBlobStore (u/abs-path (:work-dir conf) (get-in conf [k :dir]))))

(defmethod make-blob-store :oci [conf k]
  (oci/make-blob-store (get conf k)))

(defmethod make-blob-store :s3 [conf k]
  (let [s3-config (get conf k)]
    (bs3/->S3BlobStore (bs3/make-client s3-config) s3-config)))
