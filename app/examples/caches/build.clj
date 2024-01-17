(ns caches.build
  "Example script to demonstrate the use of caches"
  (:require [babashka.fs :as fs]
            [monkey.ci.build.core :as bc]))

(def cache-dir "cache")

(def caching-step
  {:name "restore-and-save-cache"
   :action (fn [ctx]
             (let [d (fs/file (get-in ctx [:step :work-dir]) cache-dir)]
               (when-not (fs/exists? d)
                 (fs/create-dir d))
               (let [f (fs/list-dir d)]
                 (println "There currently are" (count f) "files in cache")
                 ;; Write to cache
                 (spit (fs/file d (str "file-" (System/currentTimeMillis) ".txt"))
                       "Another file added to cache"))))
   :caches [{:id "example-cache"
             :path cache-dir}]})

(bc/defpipeline cached-build
  [caching-step])
