;; Build script for Monkey-ci itself
(ns monkeyci.build.script
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [monkey.ci.build
             [api :as api]
             [core :as core]
             [shell :as shell]])
  (:import [java.time OffsetDateTime ZoneOffset]
           java.time.format.DateTimeFormatter))

(defn clj-container [name dir & args]
  "Executes script in clojure container"
  {:name name
   :container/image "docker.io/clojure:temurin-21-tools-deps-alpine"
   :script [(str "cd " dir) (cs/join " " (concat ["clojure"] args))]})

(def test-lib (clj-container "test-lib" "lib" "-X:test:junit"))
(def test-app (clj-container "test-app" "app" "-M:test:junit"))

(def app-uberjar (clj-container "uberjar" "app" "-X:jar:uber"))

;; Full path to the docker config file, used to push images
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

(defn- img-script [ctx f]
  (let [tag (str base-tag ":" (image-tag ctx))
        auth (podman-auth ctx)]
    (shell/bash
     (f auth img))))

(defn build-image
  "Build the image using podman for arm and amd platforms.  Not using containers for now,
   because it gives problems when not running privileged (podman in podman running podman
   is difficult)."
  [ctx]
  (img-script
   ctx
   (partial format
            "podman build --authfile %s --platform linux/arm64,linux/amd64 --manifest %s -f docker/Dockerfile .")))

(defn push-image [ctx]
  (img-script
   ctx
   (partial format "podman manifest push --all --authfile %s %s")))

(defn publish-container [ctx name dir]
  "Executes script in clojure container that has clojars publish env vars"
  (let [env (-> (api/build-params ctx)
                (select-keys ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"]))]
    (-> (clj-container name dir "-X:jar:deploy")
        (assoc :container/env env))))

(defn publish-lib [ctx]
  (publish-container ctx "publish-lib" "lib"))

(defn publish-app [ctx]
  (publish-container ctx "publish-app" "app"))

(core/defpipeline test-all
  [test-lib
   test-app])

(core/defpipeline publish-libs
  [publish-lib
   publish-app])

(core/defpipeline publish-image
  [app-uberjar
   image-creds
   build-image
   push-image])

;; Return the pipelines
[test-all
 publish-libs
 publish-image]
