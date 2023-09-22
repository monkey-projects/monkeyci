(ns monkey.ci.config
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as cs]
            [medley.core :as mc]))

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
  (->> env
       (filter-and-strip-keys (keyword env-prefix))
       (group-keys :github)
       (group-keys :runner)))

(def default-app-config
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
      (merge (select-keys args [:dev-mode]))
      (update-in [:http :port] #(or (:port args) %))
      (update-in [:runner :type] keyword)))

(def default-script-config
  {:container-runner :docker})

(defn script-config
  "Builds config map used by the child script process"
  [env args]
  (-> default-script-config
      (deep-merge (config-from-env env))
      (merge args)
      (update :container-runner keyword)))

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
