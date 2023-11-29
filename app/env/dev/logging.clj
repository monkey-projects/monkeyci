(ns logging
  (:require [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clojure.tools.logging :as log]
            [common :as c]
            [config :as co]
            [medley.core :as mc]
            [monkey.ci.logging :as l]
            [monkey.oci.os.core :as os]))

(defn test-stream-to-bucket []
  (let [c (co/load-config "oci-config.edn")
        m (l/make-logger (:logging c))
        in (java.io.ByteArrayInputStream. (.getBytes "Hi, this is a test string"))
        logger (m c ["test.log"])]
    @(l/handle-stream logger in)))

(defn- call-os
  "Invokes `f` with object storage client and config"
  [f opts]
  (let [conf (co/oci-config :logging)
        client (os/make-client conf)]
    (f client (-> conf
                  (select-keys [:bucket-name :ns])
                  (merge opts)))))

(defn get-build-logs
  "Downloads logs for a given build from log bucket"
  [sid dir]
  (let [conf (co/oci-config :logging)
        client (os/make-client conf)
        path (l/sid->path conf nil sid)]
    (log/info "Downloading logs for" sid "at" path "into" dir)
    (when-not (fs/create-dirs dir)
      (throw (ex-info "Unable to create directories" {:dir dir})))
    @(call-os os/list-objects (when path {:prefix path}))))

(defn get-log [path]
  (-> @(call-os os/get-object {:object-name path})
      (bs/to-string)))
