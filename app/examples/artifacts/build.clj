(ns artifacts.build
  "Example script to demonstrate the use of caches"
  (:require [babashka.fs :as fs]
            [monkey.ci.api :as m]))

(def artifact-file "artifact.txt")

(def artifact
  (m/artifact "example-artifact" artifact-file))

(def create-artifact
  (-> (m/action-job
       "create-artifact"
       (fn [ctx]
         (spit (m/in-work ctx artifact-file) "This is a test artifact")))
      (m/save-artfiacts artifact)))

(def use-artifact
  (-> (m/action-job
       "use-artifact"
       (fn [ctx]
         (let [f (m/in-work ctx artifact-file)]
           (if (fs/exists? f)
             (do
               (slurp f)
               m/success)
             (assoc m/failure :message "Artifact file not found")))))
      (m/restore-artifats (m/artifact artifact))
      (m/depends-on "create-artifact")))

(def cleanup
  (-> (m/action-job
       "cleanup"
       (fn [ctx]
         (fs/delete-if-exists (m/in-work ctx artifact-file))
         m/success))
      (m/depends-on "use-artifact")))

;; Order in which you define them here does not matter, it's the dependencies
;; that determine execution order.
[create-artifact
 cleanup
 use-artifact]
