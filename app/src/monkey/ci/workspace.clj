(ns monkey.ci.workspace
  (:require [babashka.fs :as fs]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as b]
             [config :as c]
             [runtime :as rt]]))

(defn create-workspace [{:keys [checkout-dir sid] :as build} {ws :workspace}]
  (let [dest (str (cs/join "/" sid) b/extension)]
    (when checkout-dir
      (log/info "Creating workspace using files from" checkout-dir)
      @(md/chain
        (b/save ws checkout-dir dest) ; TODO Check for errors
        (constantly (assoc build :workspace dest))))))

(defn restore
  "Restores the workspace as configured in the build"
  [{:keys [build] store :workspace :as rt}]
  (let [ws (:workspace build)
        ;; Check out to the parent because the archive contains the directory
        checkout (some-> (:checkout-dir build)
                         (fs/parent)
                         str)
        restore (fn [rt]
                  (log/info "Restoring workspace" ws)
                  (md/chain
                   (b/restore store ws checkout)
                   (fn [_]
                     (assoc-in rt [:build :workspace/restored?] true))))]
    (cond-> rt
      (and store ws checkout)
      (restore))))

(defmethod c/normalize-key :workspace [k conf]
  (c/normalize-typed k conf (partial b/normalize-blob-config k)))

(defmethod rt/setup-runtime :workspace [conf k]
  (when (k conf)
    (b/make-blob-store conf k)))

