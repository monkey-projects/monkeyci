(ns monkey.ci.config
  "Configuration functionality.  This reads the application configuration from various
   sources, like environment vars or command-line args.  The configuration is structured
   in a hierarchy and optionally some values are converted.  Then this configuration is
   used to add any 'constructor functions', that are then used to create new functions to
   to some actual work.  This allows us to change the behaviour of the application with
   configuration, but also makes it possible to inject dummy functions for testing 
   purposes."
  (:require [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.walk :as cw]
            [medley.core :as mc]
            [monkey.ci.utils :as u]))

(def ^:dynamic *global-config-file* "/etc/monkeyci/config.edn")

(def env-prefix "monkeyci")

;; Determine version at compile time
(defmacro version []
  `(or (System/getenv (csk/->SCREAMING_SNAKE_CASE (str env-prefix "-version"))) "0.1.0-SNAPSHOT"))

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
         (filter-and-strip-keys env-prefix)
         (group-all-keys))))

(defn- parse-edn [p]
  (with-open [r (java.io.PushbackReader. (io/reader p))]
    (->> (edn/read r)
         (cw/prewalk (fn [x]
                       (if (map-entry? x)
                         (let [[k v] x]
                           [(csk/->kebab-case-keyword (name k)) v])
                         x))))))

(defn- parse-json [p]
  (with-open [r (io/reader p)]
    (json/parse-stream r csk/->kebab-case-keyword)))

(defn load-config-file
  "Loads configuration from given file.  This supports json and edn and converts
   keys always to kebab-case."
  [f]
  (when-let [p (some-> f
                       u/abs-path
                       io/file)]
    (when (.exists p)
      (cond
        (cs/ends-with? f ".edn") (parse-edn p)
        (cs/ends-with? f ".json") (parse-json p)))))

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
      (deep-merge (load-config-file *global-config-file*))
      (deep-merge (load-config-file (:config-file args)))
      (deep-merge (config-from-env env))
      (merge (select-keys args [:dev-mode]))
      (assoc :args args)
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

(defn- flatten-nested
  "Recursively flattens a map of maps.  Each key in the resulting map is a
   combination of the path of the parent keys."
  [path c]
  (letfn [(make-key [k]
            (->> (conj path k)
                 (map name)
                 (cs/join "-")
                 (keyword)))]
    (reduce-kv (fn [r k v]
                 (if (map? v)
                   (merge r (flatten-nested (conj path k) v))
                   (assoc r (make-key k) v)))
               {}
               c)))

(defn config->env
  "Creates a map of env vars from the config.  This is done by flattening
   the entries and prepending them with `monkeyci-`"
  [c]
  (->> c
       (flatten-nested [])
       (mc/map-keys (fn [k]
                      (keyword (str env-prefix "-" (name k)))))))
