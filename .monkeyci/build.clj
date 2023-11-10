;; Build script for Monkey-ci itself
(ns monkeyci.build.script
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [monkey.ci.build
             [api :as api]
             [core :as core]
             [shell :as shell]])
  (:import [java.time OffsetDateTime ZoneOffset]
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

(def datetime-format (DateTimeFormatter/ofPattern "yyyyMMdd"))
(def base-tag "fra.ocir.io/frjdhmocn5qi/monkeyci")
(def remote-auth "/tmp/auth.json")

(defn image-tag [ctx]
  ;; TODO Get version from context
  #_(str base-tag ":0.1.0")
  ;; Use time-based tag for now
  (->> (OffsetDateTime/now ZoneOffset/UTC)
       (.format datetime-format)))

(defn publish-image [ctx]
  (let [tag (image-tag ctx)]
    {:container/image "docker.io/dormeur/podman-qemu:latest"
     :container/mounts [[(podman-auth ctx) remote-auth]]
     :script [(format "podman build --authfile %s --platform linux/amd64,linux/arm64 -t %s -f docker/Dockerfile ."
                      remote-auth tag)
              (format "podman push --authfile %s %s" remote-auth tag)]}))

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
            publish-image]}))

;; Return the pipelines
[test-pipeline
 publish-pipeline]
