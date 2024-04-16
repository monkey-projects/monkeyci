(ns artifacts.build
  "Example script to demonstrate the use of caches"
  (:require [babashka.fs :as fs]
            [monkey.ci.build
             [core :as bc]
             [shell :as s]]))

(def artifact-file "artifact.txt")

(def create-artifact
  {:name "create-artifact"
   :action (fn [ctx]
             (spit (s/in-work ctx artifact-file) "This is a test artifact"))
   :save-artifacts [{:id "example-artifact"
                     :path artifact-file}]})

(defn cleanup [ctx]
  (fs/delete-if-exists (s/in-work ctx artifact-file))
  bc/success)

(def use-artifact
  {:name "use-artifact"
   :action (fn [ctx]
             (let [f (s/in-work ctx artifact-file)]
               (if (fs/exists? f)
                 (do
                   (slurp f)
                   bc/success)
                 (assoc bc/failure :message "Artifact file not found"))))
   :restore-artifacts [{:id "example-artifact"
                        :path artifact-file}]})

(bc/defpipeline artifact-build
  [create-artifact
   cleanup
   use-artifact])
