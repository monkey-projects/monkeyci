(ns monkey.ci.build.cache
  (:require [monkey.ci.build.artifacts :as art]))

(defn make-build-api-repository
  "Creates an `ArtifactRepository` that can be used to upload/download caches"
  [client]
  (art/->BuildApiArtifactRepository client "/cache/"))
