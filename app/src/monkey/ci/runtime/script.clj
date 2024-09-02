(ns monkey.ci.runtime.script
  "Functions for creating a runtime for build scripts"
  (:require [monkey.ci
             [artifacts :as art]
             [cache :as cache]
             [runtime :as rt]]))

(defn with-runtime [config f]
  (rt/with-runtime config :script rt
    (let [build (rt/build rt)]
      (-> rt
          (dissoc :workspace :cache :artifacts :storage)
          (assoc :artifacts (art/make-blob-repository (:artifacts rt) build)
                 :cache (cache/make-blob-repository (:cache rt) build))
          (f)))))
