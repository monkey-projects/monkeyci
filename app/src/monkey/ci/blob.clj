(ns monkey.ci.blob
  "Blob storage functionality, used to store and restore large files
   or entire directories."
  (:require [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [clompress
             [archivers :as ca]
             [compression :as cc]]
            [manifold.deferred :as md]
            [monkey.ci
             [config :as co]
             [oci :as oci]
             [protocols :as p]
             [utils :as u]]
            [monkey.ci.build.archive :as a]
            [monkey.oci.os
             [core :as os]
             [stream :as oss]])
  (:import [java.io BufferedInputStream PipedInputStream PipedOutputStream]
           [org.apache.commons.compress.archivers ArchiveStreamFactory]))

(def save p/save-blob)
(def restore p/restore-blob)
(def input-stream p/get-blob-stream)

(defmulti make-blob-store (fn [conf k]
                            (get-in conf [k :type])))

(def blob-store? p/blob-store?)

(def compression-type "gz")
(def archive-type "tar")

(def extract-archive a/extract)

(defn- drop-prefix-resolver
  "The default entry name resolver includes the full path to the file.  
   We only want the file name without the base directory, so that's what
   this resolver does."
  [base-dir path]
  ;; Skip the /
  (subs path (inc (count base-dir))))

(defn- entry-gathering-resolver
  "Adds artifact entries to the given atom"
  [entries]
  (fn [p]
    (swap! entries conj p)
    p))

(defn make-archive
  "Archives the `src` directory or file into `dest`, which should be something
   that can be converted into an `OutputStream`."
  [src dest]
  (let [prefix (u/abs-path
                (fs/file (fs/parent src)))
        entries (atom [])
        gatherer (entry-gathering-resolver entries)]
    (log/debug "Archiving" src "and stripping prefix" prefix)
    (u/ensure-dir-exists! dest)
    (with-open [os (bs/to-output-stream dest)]
      (ca/archive
       {:output-stream os
        :compression compression-type
        :archive-type archive-type
        :entry-name-resolver (comp gatherer (partial drop-prefix-resolver prefix))}
       (u/abs-path src)))
    ;; Return some info, since clompress returns `nil`
    {:src src
     :dest dest
     :entries @entries}))

(deftype DiskBlobStore [dir]
  p/BlobStore
  (save-blob [_ src dest]
    (md/success-deferred 
     (if (fs/exists? src)
       (let [f (io/file dir dest)]
         (log/debug "Saving blob" src "to" f)
         (make-archive src f))
       (do
         (log/warn "Unable to archive" src ": path does not exist")
         nil))))

  (restore-blob [_ src dest]
    (let [f (io/file dest)
          os (PipedOutputStream.)
          srcf (io/file dir src)]
      (log/debug "Restoring blob from" srcf)
      (md/future
        (when (fs/exists? srcf)
          (-> (a/extract srcf f)
              (assoc :src src))))))

  (get-blob-stream [_ src]
    (let [srcf (io/file dir src)]
      (log/debug "Retrieving blob stream for" srcf)
      (md/success-deferred
       (when (fs/exists? srcf)
         (io/input-stream srcf)))))

  (put-blob-stream [_ src dest]
    (let [f (io/file dir dest)]
      (u/mkdirs! (.getParentFile f))
      (io/copy src f)
      (md/success-deferred
       {:src src
        :dest f}))))

(defmethod make-blob-store :disk [conf k]
  ;; Make storage dir relative to the work dir
  (->DiskBlobStore (u/abs-path (:work-dir conf) (get-in conf [k :dir]))))

(defn- tmp-dir [{:keys [tmp-dir]}]
  (or tmp-dir (u/tmp-dir)))

(def extension ".tgz")

(defn- tmp-archive [conf]
  (io/file (tmp-dir conf) (str (random-uuid) extension)))

(defn- archive-obj-name [conf dest]
  (->> [(:prefix conf) dest]
       (remove nil?)
       (cs/join "/")))

(def default-oci-put-params
  ;; Increase buffer size to 16MB
  {:buf-size 0x1000000})

(def head-object (oci/retry-fn os/head-object))
(def get-object  (oci/retry-fn os/get-object))

(deftype OciBlobStore [client conf]
  p/BlobStore
  (save-blob [_ src dest]
    (if (fs/exists? src)
      (let [arch (tmp-archive conf)
            obj-name (archive-obj-name conf dest)]
        ;; Write archive to temp file first
        (log/debug "Archiving" src "to" arch)
        (make-archive src arch)
        ;; Upload the temp file
        (log/debugf "Uploading archive %s to %s (%d bytes)" arch obj-name (fs/size arch))
        (let [is (io/input-stream arch)]
          (u/log-deferred-elapsed
           ;; TODO Add retry on this
           (-> (oss/input-stream->multipart client
                                            (-> conf
                                                (select-keys [:ns :bucket-name])
                                                (merge default-oci-put-params)
                                                (assoc :object-name obj-name
                                                       :input-stream is
                                                       :close? true)))
               (md/chain (constantly obj-name))
               (md/finally #(fs/delete arch)))
           (str "Saving blob to bucket: " dest))))
      (do
        (log/warn "Unable to save blob, path does not exist:" src)
        (md/success-deferred nil))))

  (restore-blob [_ src dest]
    (let [obj-name (archive-obj-name conf src)
          f (io/file dest)
          params (-> conf
                     (select-keys [:ns :bucket-name])
                     (assoc :object-name obj-name))]
      (log/debug "Restoring blob from" src "to" dest)
      (u/log-deferred-elapsed
       (md/chain
        (head-object client params)
        (fn [exists?]
          (if exists?
            (-> (get-object client params)
                (md/chain
                 bs/to-input-stream
                 ;; Unzip and unpack in one go
                 #(a/extract % dest)
                 #(assoc % :src src)))
            ;; FIXME It may occur that a file is not yet available if it is read immediately after writing
            ;; In that case we should retry.
            (log/warn "Blob not found in bucket:" obj-name))))
       (str "Restored blob from bucket: " src))))

  (get-blob-stream [_ src]
    (let [obj-name (archive-obj-name conf src)
          params (-> conf
                     (select-keys [:ns :bucket-name])
                     (assoc :object-name obj-name))]
      (u/log-deferred-elapsed
       (md/chain
        (head-object client params)
        (fn [exists?]
          (when exists?
            (md/chain
             (get-object client params)
             bs/to-input-stream))))
       (str "Downloaded blob stream from bucket: " src))))

  (put-blob-stream [_ src dest]
    (let [obj-name (archive-obj-name conf dest)]
      (log/debug "Uploading blob stream to" obj-name)
      (u/log-deferred-elapsed
       (md/chain
        (oss/input-stream->multipart client
                                     (-> conf
                                         (select-keys [:ns :bucket-name])
                                         (merge default-oci-put-params)
                                         (assoc :object-name obj-name
                                                :input-stream src
                                                :close? true)))
        (constantly obj-name))
       (str "Uploaded blob stream to bucket: " dest)))))

(defmethod make-blob-store :oci [conf k]
  (let [oci-conf (get conf k)
        client (-> (os/make-client oci-conf)
                   (oci/add-inv-interceptor :blob))]
    (->OciBlobStore client oci-conf)))
