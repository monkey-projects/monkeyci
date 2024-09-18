(ns monkey.ci.config
  "Configuration functionality.  This reads the application configuration from various
   sources, like environment vars or command-line args.  The configuration is structured
   in a hierarchy and optionally some values are converted.  Then this configuration is
   used to add any 'constructor functions', that are then used to create new functions to
   do some actual work.  This allows us to change the behaviour of the application with
   configuration, but also makes it possible to inject dummy functions for testing 
   purposes."
  (:require [aero.core :as ac]
            [babashka.fs :as fs]
            [camel-snake-kebab.core :as csk]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [meta-merge.core :as mm]
            [monkey.aero] ; Aero extensions
            [monkey.ci
             [edn]
             [sid :as sid]
             [utils :as u]
             [version :as v]]))

(def ^:dynamic *global-config-file* "/etc/monkeyci/config.edn")
(def ^:dynamic *home-config-file* (-> (System/getProperty "user.home")
                                      (fs/path ".monkeyci" "config.edn")
                                      (fs/canonicalize)
                                      str))

(def env-prefix "monkeyci")

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

(defn strip-env-prefix [e]
  (filter-and-strip-keys env-prefix e))

(defn group-keys
  "Takes all keys in given map `m` that start with `:prefix-` and
   moves them to a submap with the prefix name, and the prefix 
   stripped from the keys.  E.g. `{:test-key 100}` with prefix `:test`
   would become `{:test {:key 100}}`"
  [m prefix]
  (let [s (filter-and-strip-keys prefix m)
        r (mc/remove-keys (key-filter prefix) m)]
    (cond-> r
      (not-empty s)
      (update prefix merge s))))

(defn group-and-merge-from-env
  "Given a map, takes all keys in `:env` that start with the given prefix 
   (using `group-keys`) and merges them with the existing submap with same 
   key.
   For example, `{:env {:test-key \"value\"} :test {:other-key \"other-value\"}}` 
   would become `{:test {:key \"value\" :other-key \"other-value\"}}`.  
   The newly grouped values overwrite any existing values."
  [m prefix]
  (let [em (filter-and-strip-keys prefix (:env m))]
    (-> m
        (update prefix merge em)
        (as-> x (mc/remove-vals nil? x)))))

(defn keywordize-type [v]
  (if (map? v)
    (mc/update-existing v :type keyword)
    v))

(defn load-config-file
  "Loads configuration from given file.  This supports json and edn and converts
   keys always to kebab-case."
  [f]
  (when (fs/exists? f)
    ;; Load using Aero
    (ac/read-config f)))

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
   {:type :inherit}
   :workspace
   {:type :disk :dir "tmp/workspace"}
   :artifacts
   {:type :disk :dir "tmp/artifacts"}
   :cache
   {:type :disk :dir "tmp/cache"}})

(defn- merge-configs [configs]
  (reduce mm/meta-merge default-app-config configs))

(defn load-raw-config
  "Loads raw (not normalized) configuration from its various sources"
  [extra-files]
  (-> (map load-config-file (concat [*global-config-file*
                                     *home-config-file*]
                                    extra-files))
      (merge-configs)
      (u/prune-tree)))

(defmulti ^:deprecated normalize-key
  "Normalizes the config as read from files and env, for the specific key.
   The method receives the entire config, that also holds the env and args
   and should return the updated config."
  (fn [k _] k))

(defmethod normalize-key :default [k c]
  (mc/update-existing c k keywordize-type))

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
                                          (select-keys [:customer-id :repo-id])
                                          (mc/assoc-some :url (:server args))))]
    (cond-> c
      (empty? (:account c)) (dissoc :account))))

(defn- dir-or-work-sub [conf k d]
  (update conf k #(or (u/abs-path %) (u/combine (abs-work-dir conf) d))))

(defmethod normalize-key :checkout-base-dir [k conf]
  (dir-or-work-sub conf k "checkout"))

(defmethod normalize-key :ssh-keys-dir [k conf]
  (dir-or-work-sub conf k "ssh-keys"))

(defmethod normalize-key :api [_ conf]
  conf)

(defmethod normalize-key :build [_ conf]
  (update conf :build (fn [b]
                        (-> b
                            (group-keys :git)
                            (group-keys :script)
                            (mc/update-existing :sid sid/parse-sid)
                            (mc/update-existing :git group-keys :author)))))

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
            (when (or (not (seqable? x))
                      (and (seqable? x) (not-empty x)))
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
                    (assoc conf :env env :args args)
                    keys-to-normalize))
        (dissoc :default :env))))

(defn app-config
  "Combines app environment with command-line args into a unified 
   configuration structure.  Args have precedence over env vars,
   which in turn override config loaded from files and default values."
  [env args]
  (-> (load-raw-config (:config-file args))
      (normalize-config (strip-env-prefix env) args)))

(defn normalize-typed
  "Convenience function that converts the `:type` of an entry into a keyword and
   then invokes `f` on it."
  [k conf f]
  (-> conf
      (update k keywordize-type)
      (f)))
