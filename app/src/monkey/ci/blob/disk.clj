(ns monkey.ci.blob.disk
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [protocols :as p]
             [utils :as u]]
            [monkey.ci.blob.common :as bc]
            [monkey.ci.build.archive :as a])
  (:import (java.io PipedOutputStream)))

(deftype DiskBlobStore [dir]
  p/BlobStore
  (save-blob [_ src dest _]
    ;; Metadata not supported
    (md/success-deferred 
     (if (fs/exists? src)
       (let [f (io/file dir dest)]
         (log/debug "Saving blob" src "to" f)
         (bc/make-archive src f))
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
