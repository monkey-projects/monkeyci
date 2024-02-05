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
             [oci :as oci]
             [utils :as u]]
            [monkey.oci.os.core :as os])
  (:import [java.io BufferedInputStream PipedInputStream PipedOutputStream]
           [org.apache.commons.compress.archivers ArchiveStreamFactory]))

(defprotocol BlobStore
  "Protocol for blob store abstraction, used to save and compress files or directories
   to some blob store, possibly remote."
  (save [store src dest] "Saves `src` file or directory to `dest` as a blob")
  (restore [store src dest] "Restores `src` to local `dest`"))

(defmulti make-blob-store (fn [conf k]
                            (get-in conf [k :type])))

(def blob-store? (partial satisfies? BlobStore))

(def compression-type "gz")
(def archive-type "tar")

(def stream-factory (ArchiveStreamFactory.))

(defn- mkdirs! [f]
  (if (and f (fs/exists? f))
    (when-not (.isDirectory f)
      (throw (ex-info "Directory cannot be created, already exists as a file" {:dir f})))
    (when-not (.mkdirs f)
      (throw (ex-info "Unable to create directory" {:dir f}))))
  f)

(defn- next-entry
  "Gets the next entry from the stream.  Due to the nature of piped streams,
   this may throw an exception when the write end is closed.  In that case, 
   we return `nil`, indicating we're at EOF."
  [ai]
  (try
    (.getNextEntry ai)
    (catch java.io.IOException ex
      (when-not (= "Write end dead" (.getMessage ex))
        ;; Some other i/o exception, rethrow it
        (throw ex)))))

(defn- extract-entry [ai e dest]
  (log/debug "Extracting entry from archive:" (.getName e))
  (let [f (io/file dest (.getName e))]
    (cond
      (.isDirectory e)
      (mkdirs! f)
      
      (.isFile e)
      (let [p (mkdirs! (.getParentFile f))]
        (with-open [os (io/output-stream f)]
          (io/copy ai os)))

      :else
      (log/warn "Unsupported archive entry:" e))))

(defn- extract-archive
  "Extraction is not supported by the lib.  This takes an input stream
   (possibly from a decompression) and a destination directory.  Returns
   the destination directory."
  [is dest]
  (log/debug "Extracting archive into" dest)
  (with-open [ai (.createArchiveInputStream stream-factory ArchiveStreamFactory/TAR is)]
    (loop [e (next-entry ai)]
      (if e
        (do
          (if (.canReadEntryData ai e)
            (extract-entry ai e dest)
            (log/warn "Unable to read entry data:" (.getName e)))
          ;; Go to next entry
          (recur (next-entry ai)))
        ;; Done
        dest))))

(defn- drop-prefix-resolver
  "The default entry name resolver includes the full path to the file.  
   We only want the file name without the base directory, so that's what
   this resolver does."
  [base-dir path]
  ;; Skip the /
  (subs path (inc (count base-dir))))

(defn make-archive
  "Archives the `src` directory or file into `dest` file."
  [src dest]
  (let [prefix (u/abs-path
                (fs/file (fs/parent src)))]
    (log/debug "Archiving" src "and stripping prefix" prefix)
    (mkdirs! (.getParentFile dest))
    (ca/archive
     {:output-stream (io/output-stream dest)
      :compression compression-type
      :archive-type archive-type
      :entryNameResolver (partial drop-prefix-resolver prefix)}
     (u/abs-path src))))

(deftype DiskBlobStore [dir]
  BlobStore
  (save [_ src dest]
    (let [f (io/file dir dest)]
      (log/debug "Saving archive" src "to" f)
      (md/chain
       (make-archive src f)
       ;; Return destination path
       (constantly (u/abs-path f)))))

  (restore [_ src dest]
    (let [f (io/file dest)
          os (PipedOutputStream.)
          srcf (io/file dir src)]
      (md/future
        (when (fs/exists? srcf)
          (with-open [is (BufferedInputStream. (PipedInputStream. os))]
            ;; Decompress to the output stream
            (doto (Thread. (fn []
                             (try 
                               (cc/decompress
                                (io/input-stream srcf)
                                os
                                compression-type)
                               (catch Exception ex
                                 (log/error "Unable to decompress archive" ex))
                               (finally
                                 (.close os)))))
              (.start))
            ;; Unarchive
            (mkdirs! f)
            (extract-archive is f)))))))

(defmethod make-blob-store :disk [conf k]
  (->DiskBlobStore (get-in conf [k :dir])))

(defn- tmp-dir [{:keys [tmp-dir]}]
  (or tmp-dir (u/tmp-dir)))

(def extension ".tgz")

(defn- tmp-archive [conf]
  (io/file (tmp-dir conf) (str (random-uuid) extension)))

(defn- archive-obj-name [conf dest]
  (->> [(:prefix conf) dest]
       (remove nil?)
       (cs/join "/")))

(deftype OciBlobStore [client conf]
  BlobStore
  (save [_ src dest]
    (let [arch (tmp-archive conf)
          obj-name (archive-obj-name conf dest)]
      ;; Write archive to temp file first
      (log/debug "Archiving" src "to" arch)
      (make-archive src arch)
      ;; Upload the temp file
      (log/debugf "Uploading archive %s to %s (%d bytes)" arch obj-name (fs/size arch))
      (-> (os/put-object client (-> conf
                                    (select-keys [:ns :bucket-name])
                                    (assoc :object-name obj-name
                                           :contents (fs/read-all-bytes arch))))
          (md/chain (constantly obj-name))
          (md/finally #(fs/delete arch)))))

  (restore [_ src dest]
    (let [obj-name (archive-obj-name conf src)
          f (io/file dest)
          arch (tmp-archive conf)
          params (-> conf
                     (select-keys [:ns :bucket-name])
                     (assoc :object-name obj-name))]
      ;; Download to tmp file
      (log/debug "Downloading" src "into" arch)
      (mkdirs! (.getParentFile arch))
      ;; FIXME Find a way to either stream the response, or write to a file without
      ;; buffering it into memory.  Right now this will go OOM on larger archives.
      (md/chain
       (os/head-object client params)
       (fn [exists?]
         (if exists?
           (-> (os/get-object client params)
               (md/chain
                bs/to-input-stream
                (fn [is]
                  (with-open [os (io/output-stream arch)]
                    (cc/decompress is os compression-type))
                  ;; Reopen the decompressed archive as a stream
                  (io/input-stream arch))
                #(extract-archive % f)
                (constantly f))
               (md/finally #(fs/delete-if-exists arch)))
           ;; FIXME It may occur that a file is not yet available if it is read immediately after writing
           (log/warn "Blob not found in bucket:" obj-name)))))))

(defmethod make-blob-store :oci [conf k]
  (let [oci-conf (oci/ctx->oci-config conf k)
        client (-> oci-conf
                   (oci/->oci-config)
                   (os/make-client))]
    (->OciBlobStore client oci-conf)))
