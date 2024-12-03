(ns monkey.ci.test.blob
  "Provides test functionality for working with blobs"
  (:require [manifold.deferred :as md]
            [monkey.ci.protocols :as p]))

(defrecord TestBlobStore [stored actions]
  p/BlobStore
  (save-blob [_ src dest md]
    (swap! stored assoc dest {:file src
                              :src dest
                              :metadata md})
    (swap! actions (fnil conj []) {:action :save
                                   :src src
                                   :dest dest
                                   :metadata md})
    (md/success-deferred {}))

  (restore-blob [_ src dest]
    (md/success-deferred
     (do
       (swap! actions (fnil conj []) {:action :restore
                                      :src src
                                      :dest dest})
       (when-let [f (get @stored src)]
         {:src src
          :dest (:file f)
          :entries []}))))

  (get-blob-stream [_ src]
    ;; TODO
    (md/error-deferred "Blob stream not supported yet" {}))

  (put-blob-stream [_ src dest]
    (md/error-deferred "Blob stream not supported yet" {}))

  (get-blob-info [_ src]
    (get @stored src)))

(defn test-store [& [stored]]
  (->TestBlobStore (atom (or stored {})) (atom [])))

(defn stored [blob]
  @(:stored blob))

(defn actions [blob]
  @(:actions blob))
