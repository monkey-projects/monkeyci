(ns caches.build
  "Example script to demonstrate the use of caches"
  (:require [babashka.fs :as fs]
            [monkey.ci.api :as m]))

(def cache-dir "cache")

(def caching-step
  (-> (m/action-job
       "restore-and-save-cache"
       (fn [ctx]
         (let [d (fs/file (get-in ctx [:job :work-dir]) cache-dir)]
           (when-not (fs/exists? d)
             (fs/create-dir d))
           (let [f (fs/list-dir d)]
             (println "There currently are" (count f) "files in cache")
             ;; Write to cache
             (spit (fs/file d (str "file-" (System/currentTimeMillis) ".txt"))
                   "Another file added to cache")))))
      (m/caches (m/cache "example-cache" cache-dir))))

[caching-step]
