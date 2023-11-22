(ns migration
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [config :refer [load-edn]]
            [monkey.ci.storage :as s]))

(defn- migrate-dir [p sid st]
  (let [files (seq (.listFiles p))
        sid-name (fn [f]
                   (let [n (.getName f)
                         l (.lastIndexOf n ".")]
                     (subs n 0 l)))
        make-sid (fn [f]
                   (concat sid [(sid-name f)]))]
    (doseq [f files]
      (cond
        (.isFile f)
        (do
          (log/info "Migrating:" f)
          (s/write-obj st (make-sid f) (load-edn f)))
        (.isDirectory f)
        (migrate-dir f (concat sid [(.getName f)]) st)))))

(defn migrate-storage
  "Migrates all files from given directory to destiny storage."
  [dir st]
  (let [f (io/file dir)]
    (log/info "Migrating storage from" (.getCanonicalPath f))
    (migrate-dir f [] st)))
