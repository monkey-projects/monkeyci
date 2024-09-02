(ns monkey.ci.workspace
  (:require [babashka.fs :as fs]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as b]
             [config :as c]
             [protocols :as p]
             [runtime :as rt]]
            [monkey.ci.build.archive :as arch]))

(defn create-workspace [{:keys [checkout-dir sid] :as build} {ws :workspace}]
  (let [dest (str (cs/join "/" sid) b/extension)]
    (when checkout-dir
      (log/info "Creating workspace using files from" checkout-dir)
      @(md/chain
        (b/save ws checkout-dir dest) ; TODO Check for errors
        (constantly (assoc build :workspace dest))))))

(defn- checkout-dir [build]
  ;; Check out to the parent because the archive contains the directory
  (some-> (:checkout-dir build)
          (fs/parent)
          str))

(defrecord BlobWorkspace [store build]
  p/Workspace
  (restore-workspace [_]
    (let [ws (:workspace build)
          checkout (checkout-dir build)]
      (if (and store ws checkout)
        (do
          (log/info "Restoring workspace" ws)
          (b/restore store ws checkout))
        ;; Did nothing if not configured
        (md/success-deferred nil)))))

(defn restore
  "Restores the workspace as configured in the build"
  [{:keys [build] ws :workspace :as rt}]
  (letfn [(->workspace [x build]
            (cond-> x
              (not (p/workspace? x))
              (->BlobWorkspace build)))]
    (md/chain
     (p/restore-workspace (->workspace ws build))
     (fn [r?]
       (cond-> rt
         r? (assoc-in [:build :workspace/restored?] true))))))

(defrecord BuildApiWorkspace [client build]
  p/Workspace
  (restore-workspace [_]
    (md/chain
     (client {:path "/workspace"
              :method :get})
     :body
     #(arch/extract % (checkout-dir build)))))

(def make-build-api-workspace ->BuildApiWorkspace)

;;; Configuration handling

(defmethod c/normalize-key :workspace [k conf]
  (c/normalize-typed k conf (partial b/normalize-blob-config k)))

(defmethod rt/setup-runtime :workspace [conf k]
  (when (k conf)
    (b/make-blob-store conf k)))

