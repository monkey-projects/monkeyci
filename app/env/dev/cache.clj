(ns cache
  (:require [config :as co]
            [monkey.ci.logging.oci :as lo]
            [monkey.oci.os.core :as os]))

(defn- call-os [f opts]
  (let [conf (co/oci-config :cache)
        client (os/make-client conf)]
    @(f client (-> conf
                   (select-keys [:bucket-name :ns])
                   (merge opts)))))

(defn list-caches
  [sid]
  (let [conf (co/oci-config :cache)
        path (lo/sid->path conf nil sid)]
    (call-os os/list-objects (when path {:prefix path}))))

(defn delete-cache
  [sid n]
  (let [conf (co/oci-config :cache)
        path (lo/sid->path conf nil sid)]
    (call-os os/delete-object {:object-name (str path "/" n)})))
