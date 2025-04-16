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
  [sid]
  (str (cs/join "/" sid) b/extension))

(defn create-workspace [{:keys [checkout-dir] :as build} {ws :workspace}]
  (let [dest (workspace-dest (build/sid build))]
    (when checkout-dir
      (log/info "Creating workspace using files from" checkout-dir)
      @(md/chain
        (b/save ws checkout-dir dest)   ; TODO Check for errors
        (constantly (assoc build :workspace dest))))))

(defrecord BlobWorkspace [store dir]
  p/Workspace
  (restore-workspace [_ sid]
    (if (and store sid dir)
      (let [src (workspace-dest sid)]
        (log/info "Restoring workspace" src)
        (b/restore store src dir))
      ;; Did nothing if not configured
      (md/success-deferred nil))))

(defn restore
  "Restores the workspace as configured in the build"
  [{:keys [sid] ws :workspace :as rt}]
  (md/chain
   (p/restore-workspace ws sid)
   (fn [r?]
     (cond-> rt
       r? (assoc :workspace/restored? true)))))

(defrecord BuildApiWorkspace [client dir]
  p/Workspace
  (restore-workspace [_ _]
    ;; sid unused, it's already known via the api request
    (md/chain
     (client {:path "/workspace"
              :method :get})
     :body
     #(arch/extract % dir))))

(def make-build-api-workspace ->BuildApiWorkspace)
