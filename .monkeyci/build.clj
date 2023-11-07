;; Build script for Monkey-ci itself
(ns monkeyci.build.script
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [monkey.ci.build
             [api :as api]
             [core :as core]
             [shell :as shell]])
  #_(:import [java.time OffsetDateTime ZoneOffset]
           java.time.format.DateTimeFormatter))

(defn clj-container [name dir & args]
  "Executes script in clojure container"
  {:name name
   :container/image "docker.io/clojure:temurin-20-tools-deps-alpine"
   :script [(str "cd " dir) (apply str "clojure " args)]})

(def test-lib (clj-container "test-lib" "lib" "-X:test:junit"))
(def test-app (clj-container "test-app" "app" "-M:test:junit"))

(def app-uberjar (clj-container "uberjar" "app" "-X:jar:uber"))

;; Full path to the docker config file, used to push images
#_(def docker-config (fs/expand-home "~/.docker/config.json"))
(defn podman-auth [ctx]
  (io/file (:checkout-dir ctx) "podman-auth.json"))

(defn image-creds
  "Fetches credentials from the params and writes them to Docker `config.json`"
  [ctx]
  (when-not (fs/exists? podman-auth)
    (println "Writing image credentials")
    (shell/param-to-file ctx "dockerhub-creds" (podman-auth ctx))))

#_(def datetime-format (DateTimeFormatter/ofPattern "yyyyMMdd-HHmm"))

#_(defn- img-tag
  "Generates a new image tag using current time"
  []
  (->> (OffsetDateTime/now ZoneOffset/UTC)
       (.format datetime-format)))

#_(def container-image
  {:name "build and push image"
   :container/image "docker.io/bitnami/kaniko:latest"
   ;; TODO Use branches and tags to determine the tag
   :container/cmd ["-d" (str "docker.io/dormeur/monkey-ci:" (img-tag)) "-f" "docker/Dockerfile" "-c" "."]
   ;; Credentials, must be mounted to /kaniko/.docker/config.json
   :container/mounts [[(str docker-config) "/kaniko/.docker/config.json"]]})

(def base-tag "fra.ocir.io/frjdhmocn5qi/monkeyci")

(defn image-tag [ctx]
  ;; TODO Get version from context
  (str base-tag ":0.1.0"))

(def build-image
  {:name "build image"
   :action (fn [ctx]
             (shell/bash "podman" "build"
                         "--authfile" (podman-auth ctx)
                         ;; QEMU needed for this
                         "--platform" "linux/amd64,linux/arm64"
                         "-t" (image-tag ctx)
                         "-f" "docker/Dockerfile"
                         "."))})

(def publish-image
  {:name "publish image"
   :action (fn [ctx]
             (shell/bash "podman" "push"
                         "--authfile" (podman-auth ctx)
                         (image-tag ctx)))})

(def test-pipeline
  (core/pipeline
   {:name "test-all"
    :steps [test-lib
            test-app]}))

(def publish-pipeline
  (core/pipeline
   {:name "publish"
    :steps [app-uberjar
            image-creds
            build-image
            publish-image]}))

;; Return the pipelines
[test-pipeline
 publish-pipeline]
