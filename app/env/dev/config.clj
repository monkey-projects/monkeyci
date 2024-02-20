(ns config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [common :as c]
            [monkey.ci
             [config :as config]
             [oci :as oci]
             [utils :as u]])
  (:import java.io.PushbackReader))

;; Global config state
(defonce global-config (atom {}))

(defn load-edn [f]
  (with-open [is (PushbackReader. (io/reader f))]
    (edn/read is)))

(defn load-config [f]
  (load-edn (io/file "dev-resources" "config" f)))

(defn load-config!
  "Loads config from `f` and adds it to the state"
  [f]
  (swap! global-config u/deep-merge (load-config f)))

(defn reset-config! []
  (reset! global-config {}))

(defn update-config! [f & args]
  (apply swap! global-config f args))

(defn load-oci-config
  "Loads config file for given env and type, or for the global env,
   and converts it to an OCI config map."
  ([env type]
   (-> (load-config (format "oci/%s-config.edn" (name env)))
       (config/normalize-config {} {})
       (get type)
       (oci/->oci-config)))
  ([type]
   (load-oci-config @c/env type)))

(defn oci-config
  "Takes global config and extracts an OCI config for given type from
   it (type being `:logging`, `:runner`, `:container`, `:storage`)"
  [type]
  (-> @global-config
      (config/normalize-config {} {})
      (get type)
      (oci/->oci-config)))

(defn oci-runner-config []
  (oci-config :runner))

(defn oci-container-config []
  (oci-config :containers))

(defn account->sid []
  (let [v (juxt :customer-id :repo-id)]
    (->> @global-config
         :account
         (v)
         (vec))))
