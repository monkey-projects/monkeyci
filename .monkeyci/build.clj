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

(defn git-ref [ctx]
  (get-in ctx [:build :git :ref]))

(defn tag-version
  "Extracts the version from the tag"
  [ctx]
  (some->> (git-ref ctx)
           (re-matches #"^refs/tags/(\d{8})$")
           (second)))

(defn image-version
  "Retrieves image version from the tag, or `staging` if this is the main branch."
  [ctx]
  (or (tag-version ctx)
      "staging"))

(defn clj-container [name dir & args]
  "Executes script in clojure container"
  {:name name
   ;; Alpine based images don't exist for arm, so use debian
   :container/image "docker.io/clojure:temurin-21-tools-deps-bookworm-slim"
   :script [(str "cd " dir) (cs/join " " (concat ["clojure"] args))]})

(def test-lib (clj-container "test-lib" "lib" "-X:test:junit"))
(def test-app (clj-container "test-app" "app" "-M:test:junit"))

(defn uberjar [name dir]
  (fn [ctx]
    (assoc (clj-container name dir "-X:jar:uber")
           :container/env {"MONKEYCI_VERSION" (image-version ctx)})))

(def app-uberjar (uberjar "app-uberjar" "app"))
(def bot-uberjar (uberjar "braid-bot-uberjar" "braid-bot"))

;; Full path to the docker config file, used to push images
(defn podman-auth [{:keys [checkout-dir]}]
  (io/file checkout-dir "podman-auth.json"))

(defn image-creds
  "Fetches credentials from the params and writes them to Docker `config.json`"
  [ctx]
  (let [auth-file (podman-auth ctx)]
    (when-not (fs/exists? auth-file)
      (shell/param-to-file ctx "dockerhub-creds" auth-file)
      (fs/delete-on-exit auth-file)
      core/success)))

(def datetime-format (DateTimeFormatter/ofPattern "yyyyMMdd"))
(def img-base "fra.ocir.io/frjdhmocn5qi")
(def app-img (str img-base "/monkeyci"))
(def bot-img (str img-base "/monkeyci-bot"))
(def gui-img (str img-base "/monkeyci-gui"))
(def remote-auth "/tmp/auth.json")

(defn ref?
  "Returns a predicate that checks if the ref matches the given regex"
  [re]
  (fn [ctx]
    (some? (some->> (git-ref ctx)
                    (re-matches re)))))

(def main-branch?
  (ref? #"^refs/heads/main$"))

(def release?
  (ref? #"^refs/tags/\d{8}$"))

(def should-publish-image?
  (some-fn main-branch? release?))

(defn- img-script
  "Runs a shell script generated by `f` and passes podman authentication and image tag."
  [ctx f base-tag version]
  (let [img (str base-tag ":" (or version (image-version ctx)))
        auth (podman-auth ctx)]
    (shell/bash
     (f auth img))))

(defn- make-context [ctx dir]
  (cond-> (:checkout-dir ctx)
    (some? dir) (str "/" dir)))

(defn kaniko-build-img
  "Creates a step that builds and uploads an image using kaniko"
  [{:keys [name dockerfile context image tag]}]
  (fn [ctx]
    {:name name
     :container/image "gcr.io/kaniko-project/executor:latest"
     :container/cmd ["--dockerfile" (str "/workspace/" (or dockerfile "Dockerfile"))
                     "--destination" (str image ":" (or tag (image-version ctx)))
                     "--context" "dir:///workspace"]
     :container/mounts [[(make-context ctx context) "/workspace"]
                        [(podman-auth ctx) "/kaniko/.docker/config.json"]]}))

(def build-app-image
  (kaniko-build-img
   {:name "publish-app-img"
    :dockerfile "docker/Dockerfile"
    :image app-img}))

(def build-bot-image
  (kaniko-build-img
   {:name "publish-bot-img"
    :context "braid-bot"
    :image bot-img}))

(def build-gui-image
  (kaniko-build-img
   {:name "publish-gui-img"
    :context "gui"
    :image gui-img}))

(defn publish [ctx name dir]
  "Executes script in clojure container that has clojars publish env vars"
  (let [env (-> (api/build-params ctx)
                (select-keys ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"])
                (assoc "MONKEYCI_VERSION" (image-version ctx)))]
    (-> (clj-container name dir "-X:jar:deploy")
        (assoc :container/env env))))

(defn publish-lib [ctx]
  (publish ctx "publish-lib" "lib"))

(defn publish-app [ctx]
  (publish ctx "publish-app" "app"))

(defn- shadow-release [n build]
  {:name n
   :container/image "docker.io/dormeur/clojure-node:1.11.1"
   :work-dir "gui"
   :script ["npm install"
            (str "npx shadow-cljs release " build)]})

(def test-gui
  (shadow-release "test-gui" :test/ci))

(def build-gui-release
  (shadow-release "release-gui" :frontend))

(core/defpipeline test-all
  ;; TODO Run these in parallel
  [test-lib
   test-app
   test-gui])

(core/defpipeline publish-libs
  [publish-lib
   publish-app])

(core/defpipeline publish-images
  [app-uberjar
   build-gui-release
   image-creds
   build-app-image
   build-gui-image])

(core/defpipeline braid-bot
  [bot-uberjar
   image-creds
   build-bot-image])

;; Return the pipelines
(defn all-pipelines [ctx]
  [test-all
   publish-libs
   ;; Publish image if necessary
   (when (should-publish-image? ctx)
     publish-images)])
