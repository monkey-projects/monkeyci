(ns monkey.ci.cli.artifacts
  "Functions for working with local artifacts"
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

(defn- try-copy [sp dp]
  (if (fs/exists? sp)
    (do
      ((if (fs/directory? sp)
         fs/copy-tree
         fs/copy)
       sp dp {:replace-existing true})
      {:src sp
       :dest dp})
    (log/debug "No artifact files found at" sp ", skipping")))

(defn save-artifact
  "Copies the artifact configured by `a` to a location in `dest`, assuming the
   source file(s) can be found in `src`."
  [src dest a]
  (let [sp (fs/path src (:path a))
        dp (fs/path (fs/create-dirs (fs/path dest (:id a))) (:path a))]
    (some-> (try-copy sp dp)
            (merge a))))

(defn restore-artifact
  "Copies the artifact configured by `a` to the configured location in `dest`,
   assuming the artifact file(s) can be found in `src` using the artifact id."
  [src dest a]
  (let [sp (fs/path src (:id a) (:path a))
        dp (fs/create-dirs (fs/path dest (:path a)))]
    (some-> (try-copy sp dp)
            (merge a))))
