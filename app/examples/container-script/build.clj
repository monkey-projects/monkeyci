(ns build
  ;; Basic build script that uses Docker
  (:require [monkey.ci.api :as m]))

[(-> (m/container-job "first-container")
     (m/image "debian:latest")
     (m/script ["echo \"I am running from Debian\""]))

 (-> (m/container-job "second-container")
     (m/image "alpine:latest")
     (m/script ["echo \"And I'm running from Alpine\""]))]
