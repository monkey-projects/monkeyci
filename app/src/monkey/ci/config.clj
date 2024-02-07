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
            [clojure
             [string :as cs]
             [walk :as cw]]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci.utils :as u]
            [monkey.ci.events.core :as ec]))

(def ^:dynamic *global-config-file* "/etc/monkeyci/config.edn")
(def ^:dynamic *home-config-file* (-> (System/getProperty "user.home")
                                      (io/file ".monkeyci" "config.edn")
                                      (.getCanonicalPath)))

(def env-prefix "monkeyci")

;; Determine version at compile time
(defmacro version []
  (or (System/getenv (csk/->SCREAMING_SNAKE_CASE (str env-prefix "-version"))) "0.1.0-SNAPSHOT"))

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

(defn group-keys
  "Takes all keys in given map `m` that start with `:prefix-` and
   moves them to a submap with the prefix name, and the prefix 
   stripped from the keys.  E.g. `{:test-key 100}` with prefix `:test`
   would become `{:test {:key 100}}`"
  [prefix m]
  (let [s (filter-and-strip-keys prefix m)]
    (-> (mc/remove-keys (key-filter prefix) m)
        (assoc prefix s))))

(defn- ^:deprecated group-credentials
  "For each of the given keys, groups `credential` into subkeys"
  [keys conf]
  (reduce (fn [r k]
            (update r k (partial group-keys :credentials)))
          conf
          keys))

(defn keywordize-type [v]
  (if (map? v)
    (mc/update-existing v :type keyword)
    v))

(defn ^:deprecated env->config
  "Takes configuration from env vars"
  [env]
  (letfn [(group-all-keys [c]
            (reduce (fn [r v]
                      (group-keys v r))
                    c
                    [:github :runner :containers :storage :api :account :http :logging :oci :build
                     :sidecar :cache :artifacts :jwk]))
          (group-build-keys [c]
            (update c :build (partial group-keys :git)))]
    (->> env
         (filter-and-strip-keys env-prefix)
         (group-all-keys)
         (group-credentials [:oci :storage :runner :logging])
         (group-build-keys)
         (u/prune-tree))))

(defn- parse-edn
  "Parses the input file as `edn` and converts keys to kebab-case."
  [p]
  (with-open [r (io/reader p)]
    (->> (u/parse-edn r)
         (cw/prewalk (fn [x]
                       (if (map-entry? x)
                         (let [[k v] x]
                           [(csk/->kebab-case-keyword (name k)) v])
                         x))))))

(defn- parse-json
  "Parses the file as `json`, converting keys to kebab-case."
  [p]
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
      (log/debug "Reading configuration file:" p)
      (letfn [(has-ext? [ext s]
                (cs/ends-with? s ext))]
        (condp has-ext? f
          ".edn" (parse-edn p)
          ".json" (parse-json p))))))

(def default-app-config
  "Default configuration for the application, without env vars or args applied."
  {:http
   {:port 3000}
   :events
   {:type :manifold}
   :runner
   {:type :child}
   :storage
   {:type :memory}
   :containers
   {:type :podman}
   :reporter
   {:type :print}
   :logging
   {:type :inherit}})

(defn- merge-configs [configs]
  (reduce u/deep-merge default-app-config configs))

(defn load-raw-config
  "Loads raw (not normalized) configuration from its various sources"
  [extra-files]
  (-> (map load-config-file (concat [*global-config-file*
                                     *home-config-file*]
                                    extra-files))
      (merge-configs)
      (u/prune-tree)))

(defn ^:deprecated keywordize-all-types [conf]
  (reduce-kv (fn [r k v]
               (assoc r k (keywordize-type v)))
             {}
             conf))

(defmulti normalize-key
  "Normalizes the config as read from files and env, for the specific key.
   The method receives the entire config, that also holds the env and args
   and should return the updated config."
  (fn [k _] k))

(defmethod normalize-key :default [k c]
  (mc/update-existing c k keywordize-type))

(defmethod normalize-key :http [_ {:keys [args] :as conf}]
  (update-in conf [:http :port] #(or (:port args) %)))

(defmethod normalize-key :dev-mode [_ conf]
  (let [r (mc/assoc-some conf :dev-mode (get-in conf [:args :dev-mode]))]
    (cond-> r
      (not (boolean? (:dev-mode r))) (dissoc :dev-mode))))

(defn abs-work-dir [conf]
  (u/abs-path (or (get-in conf [:args :workdir])
                  (:work-dir conf)
                  (u/cwd))))

(defmethod normalize-key :work-dir [_ conf]
  (assoc conf :work-dir (abs-work-dir conf)))

(defmethod normalize-key :account [_ {:keys [args] :as conf}]
  (let [c (update conf :account merge (-> args
                                          (select-keys [:customer-id :project-id :repo-id])
                                          (mc/assoc-some :url (:server args))))]
    (cond-> c
      (empty? (:account c)) (dissoc :account))))

(defn- dir-or-work-sub [conf k d]
  (update conf k #(or (u/abs-path %) (u/combine (abs-work-dir conf) d))))

(defmethod normalize-key :checkout-base-dir [k conf]
  (dir-or-work-sub conf k "checkout"))

(defmethod normalize-key :ssh-keys-dir [k conf]
  (dir-or-work-sub conf k "ssh-keys"))

(defn normalize-config
  "Given a configuration map loaded from file, environment variables and command-line
   args, applies all registered normalizers to it and returns the result.  Since the 
   order of normalizers is undefined, they should not be dependent on each other."
  [conf env args]
  (letfn [(merge-if-map [d m]
            (if (map? d)
              (merge d m)
              (or m d)))
          (nil-if-empty [x]
            (when-not (empty? x)
              x))]
    (-> (methods normalize-key)
        (keys)
        (as-> keys-to-normalize
            (reduce (fn [r k]
                      (->> (or (get env k)
                               (filter-and-strip-keys k env))
                           (nil-if-empty)
                           (merge-if-map (get conf k))
                           (mc/assoc-some r k)
                           (u/prune-tree)
                           (normalize-key k)))
                    {:env env
                     :args args}
                    keys-to-normalize))
        (dissoc :default :env))))

(defn app-config
  "Combines app environment with command-line args into a unified 
   configuration structure.  Args have precedence over env vars,
   which in turn override config loaded from files and default values."
  [env args]
  (-> (load-raw-config (:config-file args))
      (normalize-config (filter-and-strip-keys env-prefix env) args)))

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
   the entries and prepending them with `monkeyci-`.  Values are converted 
   to string."
  [c]
  (letfn [(->str [x]
            (if (keyword? x)
              (name x)
              (str x)))]
    (->> c
         (flatten-nested [])
         (mc/map-keys (fn [k]
                        (keyword (str env-prefix "-" (name k)))))
         (mc/map-vals ->str))))

(defn normalize-typed [k conf f]
  (-> conf
      (update k keywordize-type)
      (f)))
