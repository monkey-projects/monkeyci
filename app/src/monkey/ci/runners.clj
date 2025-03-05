(ns monkey.ci.runners
  "Defines runner functionality.  These depend on the application configuration.
   A runner is able to execute a build script."
  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as b]
             [build :as build]
             [errors :as err]
             [process :as p]
             [runtime :as rt]
             [script :as s]
             [spec :as spec]
             [utils :as u]
             [workspace :as ws]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.spec.build :as sb]))

(defn download-git
  "Downloads from git into a temp dir, and designates that as the working dir."
  [build rt]
  ;;(log/debug "Downloading from git using build config:" (:build rt))
  (let [git (get-in rt [:git :clone])
        conf (-> build
                 :git
                 (update :dir #(or % (build/calc-checkout-dir rt build))))
        cd (git conf)]
    (log/debug "Checking out git repo" (:url conf) "into" (:dir conf))
    (-> build
        (build/set-checkout-dir cd)
        (build/set-script-dir (build/calc-script-dir cd (build/script-dir build))))))

(defn download-src
  "Downloads the code from the remote source, if there is one.  If the source
   is already local, does nothing.  Returns an updated context."
  [build rt]
  (cond-> build
    (not-empty (:git build)) (download-git rt)))

(defn store-src
  "If a workspace configuration is present, uses it to store the source in
   the workspace.  This can then be used by other processes to download the
   cached files as needed."
  [build rt]
  (cond-> build
    (some? (:workspace rt)) (ws/create-workspace rt)))
