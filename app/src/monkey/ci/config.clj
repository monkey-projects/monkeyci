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
            [monkey.ci
             [blob :as b]
             [logging :as l]
             [utils :as u]]))

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

(defn- group-keys
  "Takes all keys in given map `m` that start with `:prefix-` and
   moves them to a submap with the prefix name, and the prefix 
   stripped from the keys.  E.g. `{:test-key 100}` with prefix `:test`
   would become `{:test {:key 100}}`"
  [prefix m]
  (let [s (filter-and-strip-keys prefix m)]
    (-> (mc/remove-keys (key-filter prefix) m)
        (assoc prefix s))))

(defn- group-credentials
  "For each of the given keys, groups `credential` into subkeys"
  [keys conf]
  (reduce (fn [r k]
            (update r k (partial group-keys :credentials)))
          conf
          keys))

(defn- config-from-env
  "Takes configuration from env vars"
  [env]
  (letfn [(group-all-keys [c]
            (reduce (fn [r v]
                      (group-keys v r))
                    c
                    [:github :runner :containers :storage :api :account :http :logging :oci :build
                     :sidecar :cache]))
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

(defn- set-work-dir [conf]
  (assoc conf :work-dir (u/abs-path (or (get-in conf [:args :workdir])
                                        (:work-dir conf)
                                        (u/cwd)))))

(defn- set-checkout-base-dir [conf]
  (update conf :checkout-base-dir #(or (u/abs-path %) (u/combine (:work-dir conf) "checkout"))))

(defn- set-log-dir [conf]
  (update conf
          :logging
          (fn [{:keys [type] :as c}]
            (cond-> c
              ;; FIXME This check should be centralized in logging ns
              (= :file type) (update :dir #(or (u/abs-path %) (u/combine (:work-dir conf) "logs")))))))

(defn- set-account
  "Updates the `:account` in the config with cli args"
  [{:keys [args] :as conf}]
  (let [c (update conf :account merge (-> args
                                          (select-keys [:customer-id :project-id :repo-id])
                                          (mc/assoc-some :url (:server args))))]
    (cond-> c
      (empty? (:account c)) (dissoc :account))))

(defn app-config
  "Combines app environment with command-line args into a unified 
   configuration structure.  Args have precedence over env vars,
   which in turn override config loaded from files and default values."
  [env args]
  (-> (map load-config-file (concat [*global-config-file*
                                     *home-config-file*]
                                    (:config-file args)))
      (concat [(config-from-env env)])
      (merge-configs)
      (merge (select-keys args [:dev-mode]))
      (assoc :args args)
      (update-in [:http :port] #(or (:port args) %))
      (update-in [:runner :type] keyword)
      (update-in [:storage :type] keyword)
      (update-in [:logging :type] keyword)
      (set-work-dir)
      (set-checkout-base-dir)
      (set-log-dir)
      (set-account)))

(defn- configure-blob [k ctx]
  (mc/update-existing ctx k (fn [c]
                              (when (some? (:type c))
                                (assoc c :store (b/make-blob-store ctx k))))))

(def configure-workspace (partial configure-blob :workspace))
(def configure-cache     (partial configure-blob :cache))

(def default-script-config
  "Default configuration for the script runner."
  {:containers {:type :docker}
   :storage {:type :memory}
   :logging {:type :inherit}})

(defn initialize-log-maker [conf]
  (assoc-in conf [:logging :maker] (l/make-logger conf)))

(defn initialize-log-retriever [conf]
  (assoc-in conf [:logging :retriever] (l/make-log-retriever conf)))

(defn script-config
  "Builds config map used by the child script process"
  [env args]
  (-> default-script-config
      (u/deep-merge (config-from-env env))
      (merge args)
      (update-in [:containers :type] keyword)
      (update-in [:logging :type] keyword)
      (update-in [:cache :type] keyword)
      (initialize-log-maker)
      (configure-cache)
      (mc/update-existing-in [:build :sid] u/parse-sid)))

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
