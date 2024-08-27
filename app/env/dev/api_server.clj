(ns api-server
  "For trying out the build api server"
  (:require [babashka.fs :as fs]
            [monkey.ci.blob :as blob]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.events.core :as ec]))

(defonce server (atom nil))

(def work-dir "tmp/build-server")

(defn abs-wd [subdir]
  (str (fs/canonicalize (fs/path work-dir subdir))))

(def default-config
  {:events (ec/make-events {:events {:type :manifold}})
   :workspace (blob/->DiskBlobStore (abs-wd "workspace"))
   :artifacts (blob/->DiskBlobStore (abs-wd "artifacts"))
   :cache (blob/->DiskBlobStore (abs-wd "cache"))})

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

