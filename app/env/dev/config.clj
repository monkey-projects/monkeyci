(ns config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [monkey.ci.oci :as oci])
  (:import java.io.PushbackReader))

(defn load-edn [f]
  (with-open [is (PushbackReader. (io/reader f))]
    (edn/read is)))

(defn load-config [f]
  (load-edn (io/file "dev-resources" f)))

(defn oci-config [env type]
  (-> (load-config (format "oci/%s-config.edn" (name env)))
      (oci/ctx->oci-config type)
      (oci/->oci-config)))

(defn oci-runner-config []
  (oci-config :staging :runner))
