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
(def bot-uberjar (clj-container "braid-bot-uberjar" "braid-bot" "-X:jar:uber"))

;; Full path to the docker config file, used to push images
(defn podman-auth [ctx]
  (io/file (:checkout-dir ctx) "podman-auth.json"))

(defn image-creds
  "Fetches credentials from the params and writes them to Docker `config.json`"
  [ctx]
  (let [auth-file (podman-auth ctx)]
    (when-not (fs/exists? auth-file)
      (shell/param-to-file ctx "dockerhub-creds" auth-file)
      (fs/delete-on-exit auth-file)
      core/success)))

(def datetime-format (DateTimeFormatter/ofPattern "yyyyMMdd"))
(def app-img "fra.ocir.io/frjdhmocn5qi/monkeyci")
(def bot-img "fra.ocir.io/frjdhmocn5qi/monkeyci-bot")
(def remote-auth "/tmp/auth.json")

(defn image-version
  "Retrieves image version.  Ideally from context (e.g. commit tag), but
   currently this still is the date."
  [ctx]
  ;; TODO Get version from context
  #_(str base-tag ":0.1.0")
  ;; Use time-based tag for now
  (->> (OffsetDateTime/now ZoneOffset/UTC)
       (.format datetime-format)))

(defn- img-script
  "Runs a shell script generated by `f` and passes podman authentication and image tag."
  [ctx f base-tag]
  (let [img (str base-tag ":" (image-version ctx))
        auth (podman-auth ctx)]
    (shell/bash
     (f auth img))))

(defn- podman-build-cmd [dockerfile dir]
  (format "podman build --authfile %%s --platform linux/arm64,linux/amd64 --manifest %%s -f %s %s" dockerfile dir))

(defn build-image
  "Build the image using podman for arm and amd platforms.  Not using containers for now,
   because it gives problems when not running privileged (podman in podman running podman
   is difficult)."
  [ctx dockerfile img & [dir]]
  (img-script ctx (partial format (podman-build-cmd dockerfile (or dir "."))) img))

(defn push-image [ctx img]
  (img-script ctx (partial format "podman manifest push --all --authfile %s %s") img))

(defn build-app-image [ctx]
  (build-image ctx "docker/Dockerfile" app-img))

(defn push-app-image [ctx]
  (push-image ctx app-img))

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

(defn build-bot-image [ctx]
  (build-image ctx "braid-bot/Dockerfile" bot-img "braid-bot"))

(defn push-bot-image [ctx]
  (push-image ctx bot-img))

(def test-gui
  {:name "test-gui"
   :container/image "docker.io/cimg/clojure:1.11-node"
   :script ["npm install"
            "npx shadow-cljs release :test/ci"]})

(core/defpipeline test-all
  ;; TODO Run these in parallel
  [test-lib
   test-app
   test-gui])

(core/defpipeline publish-libs
  [publish-lib
   publish-app])

(core/defpipeline publish-image
  [app-uberjar
   image-creds
   build-app-image
   push-app-image])

(core/defpipeline braid-bot
  [bot-uberjar
   image-creds
   build-bot-image
   push-bot-image])

#_(core/defpipeline publish-gui
  [test-gui])

;; Return the pipelines
[test-all
 publish-libs
 publish-image
 #_braid-bot]
