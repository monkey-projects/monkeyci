(ns api-server
  "For trying out the build api server"
  (:require [babashka.fs :as fs]
            [config :as co]
            [monkey.ci
             [blob :as blob]
             [runtime :as rt]
             [storage :as st]]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.events.core :as ec]))

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

(defn global->config []
  (-> @co/global-config
      (rt/config->runtime)
      (select-keys [:events :workspace :artifacts :cache :storage])))

(defn stop-server []
  (swap! server (fn [s]
                  (when-let [server (:server s)]
                    (.close server))
                  nil)))

(defn start-server [& [conf]]
  (swap! server (fn [s]
                  (when-let [server (:server s)]
                    (.close server))
                  (bas/start-server (merge default-config conf)))))

