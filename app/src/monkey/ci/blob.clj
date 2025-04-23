(ns monkey.ci.blob
  "Blob storage functionality, used to store and restore large files
   or entire directories."
  (:require [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clojure.java
             [classpath :as cp]
             [io :as io]]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [clompress.archivers :as ca]
            [java-time.api :as jt]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [oci :as oci]
             [protocols :as p]
             [utils :as u]]
            [monkey.ci.build.archive :as a]
            [monkey.oci.os
             [core :as os]
             [martian :as om]
             [stream :as oss]])
  (:import (java.io PipedOutputStream)))

(defn save
  "Saves blob from src to dest, with optional metadata"
  [blob src dest & [md]]
  (p/save-blob blob src dest md))

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
  (if (= path base-dir)
    "."
    ;; Skip the /
    (subs path (inc (count base-dir)))))

(defn- entry-gathering-resolver
  "Adds artifact entries to the given atom"
  [entries]
  (fn [p]
    (swap! entries conj p)
    p))

(defn- set-mode
  "Sets the TAR entry file mode using the posix file permissions"
  [entry]
  (.setMode entry (-> (.getFile entry)
                      (fs/posix-file-permissions)
                      (a/posix->mode))))

(defn make-archive
  "Archives the `src` directory or file into `dest`, which should be something
   that can be converted into an `OutputStream`."
  [src dest]
  ;; The prefix to drop is the directory where the files are in.  If the source is
  ;; a single file, we mean its containing directory, otherwise the entire directory
  ;; should be dropped.
  (let [prefix (u/abs-path
                (cond-> src
                  (not (fs/directory? src)) (fs/parent)))
        entries (atom [])
        gatherer (entry-gathering-resolver entries)]
    (log/debug "Archiving" src "and stripping prefix" prefix)
    (u/ensure-dir-exists! dest)
    (with-open [os (bs/to-output-stream dest)]
      (ca/archive
       {:output-stream os
        :compression compression-type
        :archive-type archive-type
        :entry-name-resolver (comp gatherer (partial drop-prefix-resolver prefix))
        :before-add set-mode}
       (u/abs-path src)))
    ;; Return some info, since clompress returns `nil`
    {:src src
     :dest dest
     :entries @entries}))

(deftype DiskBlobStore [dir]
  p/BlobStore
  (save-blob [_ src dest _]
    ;; Metadata not supported
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
        :dest f})))

  (get-blob-info [_ src]
    (let [f (fs/path dir src)]
      (md/success-deferred
       (when (fs/exists? f)
         {:src src
          :size (fs/size f)})))))

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

(def meta-prefix "opc-meta-")

(defn- convert-meta
  "Prefixes metadata with `opc-meta-` as required by oci"
  [md]
  (letfn [(->meta [k]
            ;; Write as string otherwise we risk conversion
            (str meta-prefix (name k)))]
    (some->> md
             (map (fn [[k v]]
                    [(->meta k) v]))
             (into {}))))

(defn- parse-meta
  "Extracts metadata from the headers, i.e. those that start with `opc-meta-`."
  [headers]
  (reduce-kv (fn [r k v]
               (cond-> r
                 (.startsWith (name k) meta-prefix) (assoc (keyword (subs (name k) (count meta-prefix))) v)))
             {}
             headers))

(deftype OciBlobStore [client conf]
  p/BlobStore
  (save-blob [_ src dest md]
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
                                                       :close? true)
                                                (mc/assoc-some :metadata (convert-meta md))))
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
            ;; It may occur that a file is not yet available if it is read immediately after writing
            ;; In that case we should retry.  Currently, this is left up to the higher level.
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
       (str "Uploaded blob stream to bucket: " dest))))

  (get-blob-info [_ src]
    (let [obj-name (archive-obj-name conf src)
          params (-> conf
                     (select-keys [:ns :bucket-name])
                     (assoc :object-name obj-name))]
      (md/chain
       (om/head-object client params)
       (fn [{:keys [status headers]}]
         (when (= 200 status)
           (-> {:size (Integer/parseInt (:content-length headers))
                :metadata (parse-meta headers)}
               (mc/assoc-some
                :last-modified (some->> (:last-modified headers)
                                        (jt/instant (jt/formatter :rfc-1123-date-time)))))))))))

(defmethod make-blob-store :oci [conf k]
  (let [oci-conf (get conf k)
        client (-> (os/make-client oci-conf)
                   (oci/add-inv-interceptor :blob))]
    (->OciBlobStore client oci-conf )))
