(ns migration
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [config :refer [load-edn]]
            [storage :refer [make-storage]]
            [medley.core :as mc]
            [monkey.ci
             [protocols :as p]
             [storage :as s]]))

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
          (p/write-obj st (make-sid f) (load-edn f)))
        (.isDirectory f)
        (migrate-dir f (concat sid [(.getName f)]) st)))))

(defn migrate-from-file-storage
  "Migrates all files from given directory to destiny storage."
  [dir st]
  (let [f (io/file dir)]
    (log/info "Migrating storage from" (.getCanonicalPath f))
    (migrate-dir f [] st)))

(defn- migrate-customer [cust-id src dest]
  (log/debug "Migrating customer:" cust-id)
  (let [cust (s/find-customer src cust-id)]
    (s/save-customer dest cust)))

(defn migrate-to-storage
  "Migrates entities from the given storage to the destination storage by
   listing the customers and migrating their properties and builds."
  [src dest]
  (let [cust (p/list-obj src [s/global "customers"])]
    (log/info "Migrating" (count cust) "customers")
    (doseq [c cust]
      (migrate-customer c src dest))))
