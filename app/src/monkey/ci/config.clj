(ns monkey.ci.config
  (:require [medley.core :as mc]))

;; Determine version at compile time
(defmacro version []
  `(or (System/getenv "MONKEYCI_VERSION") "0.1.0-SNAPSHOT"))

(def default-config
  {:http
   {:port 3000}})

(def deep-merge (partial merge-with merge))

(defn- key-filter [prefix]
  (let [exp (str (name prefix) "-")]
    #(.startsWith (name %) exp)))

(defn- strip-prefix [prefix]
  (fn [k]
    (keyword (subs (name k) (inc (count (name prefix)))))))

(defn- filter-and-strip-keys [prefix m]
  (->> m
       (mc/filter-keys (key-filter prefix))
       (mc/map-keys (strip-prefix prefix))))

(defn- group-keys
  "Takes all keys in given map `m` that start with `:prefix-` and
   moves them to a submap with the prefix name, and the prefix 
   stripped from the keys.  E.g. `{:test-key 100}` with prefix `:test`
   would become `{:test {:key 100}}`"
  [prefix m]
  (let [s (filter-and-strip-keys prefix m)]
    (-> (mc/remove-keys (key-filter prefix) m)
        (assoc prefix s))))

(defn- config-from-env
  "Takes configuration from env vars"
  [env]
  (->> env
       (filter-and-strip-keys :monkeyci)
       (group-keys :github)))

(defn build-config
  "Combines app environment with command-line args into a unified 
   configuration structure.  Args have precedence over env vars,
   which in turn override default values."
  [env args]
  (-> default-config
      (deep-merge (config-from-env env))
      (update-in [:http :port] #(or (:port args) %))))
