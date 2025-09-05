(ns monkey.ci.config
  "Configuration functionality.  This reads the application configuration from various
   sources, like environment vars or command-line args.  It provides some defaults and
   accessor functions.  It mainly uses aero to reduce duplication in the configuration
   and merge multiple config files into one."
  (:require [aero.core :as ac]
            [babashka.fs :as fs]
            [config.core :as cc]
            [medley.core :as mc]
            [meta-merge.core :as mm]
            [monkey.aero] ; Aero extensions
            [monkey.ci
             [edn]
             [utils :as u]]))

(defn user-home []
  (System/getProperty "user.home"))

(defn xdg-config-home []
  (or (System/getenv "XDG_CONFIG_HOME")
      (-> (fs/path (user-home) ".config")
          (fs/canonicalize)
          str)))

(def ^:dynamic *global-config-file* "/etc/monkeyci/config.edn")
(def ^:dynamic *home-config-file* (-> (xdg-config-home)
                                      (fs/path "monkeyci" "config.edn")
                                      (fs/canonicalize)
                                      str))

(defn load-config-file
  "Loads configuration from given file.  This supports json and edn and converts
   keys always to kebab-case."
  [f]
  (when (fs/exists? f)
    ;; Load using Aero
    (ac/read-config f)))

(defn load-config-env [v]
  (when v
    (with-open [r (java.io.StringReader. v)]
      (ac/read-config r))))

(def max-script-timeout
  "Max msecs a build script can run before we terminate it"
  ;; One hour
  (* 3600 1000))

(def free-credits 1000) ; Maybe we should make this configurable?

(def default-app-config
  "Default configuration for the application, without env vars or args applied."
  {:http
   {:port 3000}
   :events
   {:type :manifold}
   :runner
   {:type :local}
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
  [extra-files env]
  (-> (mapv load-config-file (concat [*global-config-file*
                                      *home-config-file*]
                                     extra-files))
      (conj (load-config-env (:monkeyci-config env)))
      (merge-configs)
      (u/prune-tree)))

(defn- apply-args
  "Applies any CLI arguments to the config.  These have the highest priority and overwrite
   any existing config."
  [config args]
  (letfn [(http-port [x]
            (update x :http mc/assoc-some :port (:port args)))
          (dev-mode [x]
            (mc/assoc-some x :dev-mode (:dev-mode args)))
          (work-dir [x]
            (update x :work-dir (comp u/abs-path #(or (:workdir args) % (u/cwd)))))
          (account [x]
            (let [acc (-> (select-keys args [:org-id :repo-id])
                          (mc/assoc-some :url (:server args)))]
              (cond-> x
                (not-empty acc) (assoc :account acc))))
          (do-apply [conf f]
            (f conf))]
    (let [cli-args [http-port
                    dev-mode
                    work-dir
                    account]]
      (-> config
          (assoc :args args)
          (as-> x (reduce do-apply x cli-args))))))

(defn app-config
  "Combines app environment with command-line args into a unified 
   configuration structure.  Args have precedence over env vars,
   which in turn override config loaded from files and default values."
  [env args]
  (-> (load-raw-config (:config-file args) env)
      (apply-args args)))
