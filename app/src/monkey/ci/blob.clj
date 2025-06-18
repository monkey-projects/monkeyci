(ns monkey.ci.blob
  "Blob storage functionality, used to store and restore large files
   or entire directories."
  (:require [monkey.ci
             [oci :as oci]
             [protocols :as p]
             [utils :as u]]
            [monkey.ci.blob
             [common :as bc]
             [disk :as bd]
             [oci :as bo]]
            [monkey.oci.os.core :as os]))

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

(def extension bc/extension)

(defmethod make-blob-store :oci [conf k]
  (let [oci-conf (get conf k)
        client (-> (os/make-client oci-conf)
                   (oci/add-inv-interceptor :blob))]
    (bo/->OciBlobStore client oci-conf )))
