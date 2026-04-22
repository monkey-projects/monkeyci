(ns monkey.ci.build.artifacts
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as blob]
             [protocols :as p]]
            [monkey.ci.build
             [api :as api]
             [archive :as archive]]
            [monkey.ci.utils :as u]))

(defrecord BuildApiArtifactRepository [client base-path]
  p/ArtifactRepository
  (restore-artifact [this _ id dest]
    (log/debug "Restoring artifact using build API:" id "to" dest)
    (u/log-deferred-elapsed
     (-> (client {:method :get
                  :path (str base-path id)
                  :as :stream})
         (md/chain
          :body
          #(archive/extract % dest))
         (md/catch (fn [ex]
                     (if (= 404 (:status (ex-data ex)))
                       (log/warn "Artifact not found:" id)
                       (throw ex)))))
     (str "Restored artifact from build API: " id)))

  (save-artifact [this _ id src]
    (let [tmp (fs/create-temp-file)
          ;; TODO Skip the tmp file intermediate step, it takes up disk space and is slower
          arch (try
                 (blob/make-archive src (fs/file tmp))
                 (catch Exception ex
                   (log/error "Unable to create archive from" src ex)
                   (throw ex)))
          stream (io/input-stream (fs/file tmp))]
      (log/debugf "Uploading artifact/cache to api server: %s from %s (compressed size: %.2f MB)" id src (u/file-size-mb arch))
      (u/log-deferred-elapsed
       (-> (client (api/as-edn {:method :put
                                :path (str base-path id)
                                :body stream}))
           (md/chain
            :body
            (partial merge arch))
           (md/finally
             ;; Clean up
             (fn []
               (.close stream)
               (fs/delete tmp))))
       (str "Saved artifact to build API: " id)))))

(defn make-build-api-repository [client]
  (->BuildApiArtifactRepository client "/artifact/"))
