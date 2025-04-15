(ns monkey.ci.workspace
  (:require [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as b]
             [build :as build]
             [protocols :as p]]
            [monkey.ci.build.archive :as arch]))

(defn workspace-dest
  "Determines the workspace destination path for this build"
  [build]
  (str (cs/join "/" (build/sid build)) b/extension))

(defn create-workspace [{:keys [checkout-dir] :as build} {ws :workspace}]
  (let [dest (workspace-dest build)]
    (when checkout-dir
      (log/info "Creating workspace using files from" checkout-dir)
      @(md/chain
        (b/save ws checkout-dir dest)   ; TODO Check for errors
        (constantly (assoc build :workspace dest))))))

(defrecord BlobWorkspace [store build]
  p/Workspace
  (restore-workspace [_]
    (let [ws (:workspace build)
          checkout (build/checkout-dir build)]
      (if (and store ws checkout)
        (do
          (log/info "Restoring workspace" ws)
          (b/restore store ws checkout))
        ;; Did nothing if not configured
        (md/success-deferred nil)))))

(defn restore
  "Restores the workspace as configured in the build"
  [{:keys [build] ws :workspace :as rt}]
  (letfn [(->workspace [x]
            (cond-> x
              (not (p/workspace? x))
              (->BlobWorkspace build)))]
    (md/chain
     (p/restore-workspace (->workspace ws))
     (fn [r?]
       (cond-> rt
         r? (assoc-in [:build :workspace/restored?] true))))))

(defrecord BuildApiWorkspace [client build]
  p/Workspace
  (restore-workspace [_]
    (log/info "Restoring workspace for build" (build/sid build) "using build api")
    (md/chain
     (client {:path "/workspace"
              :method :get})
     :body
     #(arch/extract % (build/checkout-dir build)))))

(def make-build-api-workspace ->BuildApiWorkspace)
