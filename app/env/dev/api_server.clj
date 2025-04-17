(ns api-server
  "For trying out the build api server"
  (:require [babashka.fs :as fs]
            [com.stuartsierra.component :as csc]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as blob]
             [cache :as cache]
             [protocols :as p]
             [storage :as st]]
            [monkey.ci.build
             [api-server :as bas]
             [api :as ba]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.runners.runtime :as rr]))

(defonce server (atom nil))

(def work-dir "tmp/build-server")

(defn abs-wd [subdir]
  (str (fs/canonicalize (fs/path work-dir subdir))))

(defn- blob [dir]
  (blob/->DiskBlobStore (abs-wd dir)))

(def default-config
  {:events {:type :manifold}
   :workspace {:type :disk :dir (abs-wd "workspace")}
   :artifacts {:type :disk :dir (abs-wd "artifacts")}
   :cache {:type :disk :dir (abs-wd "caches")}
   :build {:sid ["test-cust" "test-repo" "test-build"]}
   :containers {:type :podman}})

(defn start-system [conf]
  (-> (rr/make-runner-system conf)
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
     (p/restore-artifact repo nil id dest)
     #(assoc % :dest dest))))
