(ns api-server
  "For trying out the build api server"
  (:require [babashka.fs :as fs]
            [com.stuartsierra.component :as csc]
            [manifold.deferred :as md]
            [monkey.ci
             [artifacts :as art]
             [blob :as blob]
             [cache :as cache]
             [storage :as st]]
            [monkey.ci.build
             [api-server :as bas]
             [api :as ba]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.runtime.app :as ra]))

(defonce server (atom nil))

(def work-dir "tmp/build-server")

(defn abs-wd [subdir]
  (str (fs/canonicalize (fs/path work-dir subdir))))

(defn- blob [dir]
  (blob/->DiskBlobStore (abs-wd dir)))

(def default-config
  {:events (ec/make-events {:type :manifold})
   :workspace (blob "workspace")
   :artifacts (blob "artifacts")
   :cache (blob "cache")
   :storage (st/make-memory-storage)
   :build {:sid ["test-cust" "test-repo" "test-build"]}})

(defn start-system [conf]
  (-> (ra/make-runner-system conf)
      (csc/start)))

(defn stop-system [sys]
  (csc/stop sys))

(defn stop-server []
  (swap! server (fn [s]
                  (when s
                    (stop-system s))
                  nil)))

(defn start-server [& [conf]]
  (swap! server (fn [s]
                  (when s
                    (stop-system s))
                  (start-system (merge default-config conf)))))

(defn make-client []
  (let [{:keys [token port]} (:api-config @server)]
    (when-not token
      (throw (ex-info "Server not started or it does not contain api configuration" @server)))
    (ba/make-client (str "http://localhost:" port) token)))

(defn download-cache [client id]
  (let [repo (cache/make-build-api-repository client)
        dest (fs/file (fs/create-temp-dir))]
    (md/chain
     (art/restore-artifact repo id dest)
     #(assoc % :dest dest))))
