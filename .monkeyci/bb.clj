(ns bb
  "Functions for running babashka tasks in a container"
  (:require [monkey.ci
             [api :as m]
             [extensions :as ext]]))

(defn bb-job
  "Base configuration for a babashka job"
  [id]
  (-> (m/container-job id)
      (m/image "docker.io/monkeyci/bb-tf:latest")))

(defn configure-bb-job
  "Extension before-handler that configures the job to run babashka"
  [ctx]
  (ext/update-job ctx merge bb-job))
