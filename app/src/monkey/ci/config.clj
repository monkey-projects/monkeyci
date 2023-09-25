(ns monkey.ci.config
  "Configuration functionality.  This reads the application configuration from various
   sources, like environment vars or command-line args.  The configuration is structured
   in a hierarchy and optionally some values are converted.  Then this configuration is
   used to add any 'constructor functions', that are then used to create new functions to
   to some actual work.  This allows us to change the behaviour of the application with
   configuration, but also makes it possible to inject dummy functions for testing 
   purposes."
  (:require [medley.core :as mc]))

;; Determine version at compile time
(defmacro version []
  `(or (System/getenv "MONKEYCI_VERSION") "0.1.0-SNAPSHOT"))

(defn- merge-if-map [a b]
  (if (map? a)
    (merge a b)
    b))

(def deep-merge (partial merge-with merge-if-map))

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
  (letfn [(group-all-keys [c]
            (reduce (fn [r v]
                      (group-keys v r))
                    c
                    [:github :runner :containers]))]
    (->> env
         (filter-and-strip-keys :monkeyci)
         (group-all-keys))))

(def default-app-config
  "Default configuration for the application, without env vars or args applied."
  {:http
   {:port 3000}
   :runner
   {:type :child}})

(defn app-config
  "Combines app environment with command-line args into a unified 
   configuration structure.  Args have precedence over env vars,
   which in turn override default values."
  [env args]
  (-> default-app-config
      (deep-merge (config-from-env env))
      (update-in [:http :port] #(or (:port args) %))
      (update-in [:runner :type] keyword)))

(def default-script-config
  "Default configuration for the script runner."
  {:containers {:type :docker}})

(defn script-config
  "Builds config map used by the child script process"
  [env args]
  (-> default-script-config
      (deep-merge (config-from-env env))
      (merge args)
      (update-in [:containers :type] keyword)))
