(ns cache
  (:require [config :as co]
            [monkey.ci.logging :as l]
            [monkey.oci.os.core :as os]))

(defn- call-os [f opts]
  (let [conf (co/oci-config :cache)
        client (os/make-client conf)]
    @(f client (-> conf
                   (select-keys [:bucket-name :ns])
                   (merge opts)))))

(defn list-caches
  "Downloads logs for a given build from log bucket"
  [sid]
  (let [conf (co/oci-config :cache)
        path (l/sid->path conf nil sid)]
    (call-os os/list-objects (when path {:prefix path}))))

(defn delete-cache
  [sid n]
  (let [conf (co/oci-config :cache)
        path (l/sid->path conf nil sid)]
    (call-os os/delete-object {:object-name (str path "/" n)})))
