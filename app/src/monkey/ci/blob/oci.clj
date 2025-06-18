(ns monkey.ci.blob.oci
  "Blob implementation that uses OCI buckets"
  (:require [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [java-time.api :as jt]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [oci :as oci]
             [protocols :as p]
             [utils :as u]]
            [monkey.ci.blob.common :as c]
            [monkey.ci.build.archive :as a]
            [monkey.oci.os
             [core :as os]
             [martian :as om]
             [stream :as oss]]))

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
      (let [arch (c/tmp-archive conf)
            obj-name (archive-obj-name conf dest)]
        ;; Write archive to temp file first
        (log/debug "Archiving" src "to" arch)
        (c/make-archive src arch)
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
