;; Build script for Monkey-ci itself
(ns monkeyci.build.script
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [monkey.ci.build
             [api :as api]
             [core :as core]
             [shell :as shell]]))

(defn git-ref [ctx]
  (get-in ctx [:build :git :ref]))

(def tag-regex #"^refs/tags/(\d+\.\d+\.\d+(\.\d+)?$)")

(defn tag-version
  "Extracts the version from the tag"
  [ctx]
  (some->> (git-ref ctx)
           (re-matches tag-regex)
           (second)))

(defn image-version
  "Retrieves image version from the tag, or `latest` if this is the main branch."
  [ctx]
  (or (tag-version ctx)
      "latest"))

(defn lib-version
  "Retrieves lib/jar version from the tag, or the next snapshot if this is the main branch."
  [ctx]
  (or (tag-version ctx)
      ;; TODO Determine automatically
      "0.2.3-SNAPSHOT"))

(defn clj-container [name dir & args]
  "Executes script in clojure container"
  {:name name
   ;; Alpine based images don't exist for arm, so use debian
   :container/image "docker.io/clojure:temurin-21-tools-deps-bookworm-slim"
   :script [(str "cd " dir) (cs/join " " (concat ["clojure" "-Sdeps" "'{:mvn/local-repo \"../m2\"}'"] args))]
   :caches [{:id "mvn-local-repo"
             :path "m2"}]})

(def test-lib (clj-container "test-lib" "lib" "-X:test:junit"))
(def test-app (clj-container "test-app" "app" "-M:test:junit"))

(defn uberjar [name dir]
  {:name name
   :action
   (fn [ctx]
     (assoc (clj-container name dir "-X:jar:uber")
            :container/env {"MONKEYCI_VERSION" (lib-version ctx)}))})

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

(def img-base "fra.ocir.io/frjdhmocn5qi")
(def app-img (str img-base "/monkeyci"))
(def bot-img (str img-base "/monkeyci-bot"))
(def gui-img (str img-base "/monkeyci-gui"))

(defn ref?
  "Returns a predicate that checks if the ref matches the given regex"
  [re]
  (fn [ctx]
    (some? (some->> (git-ref ctx)
                    (re-matches re)))))

(def main-branch?
  (ref? #"^refs/heads/main$"))

(def release?
  (ref? tag-regex))

(def should-publish?
  (some-fn main-branch? release?))

(defn- make-context [ctx dir]
  (cond-> (:checkout-dir ctx)
    (some? dir) (str "/" dir)))

(defn kaniko-build-img
  "Creates a step that builds and uploads an image using kaniko"
  [{:keys [name dockerfile context image tag]}]
  {:name name
   :action
   (fn [ctx]
     {:container/image "gcr.io/kaniko-project/executor:latest"
      :container/cmd ["--dockerfile" (str "/workspace/" (or dockerfile "Dockerfile"))
                      "--destination" (str image ":" (or tag (image-version ctx)))
                      "--context" "dir:///workspace"]
      :container/mounts [[(make-context ctx context) "/workspace"]
                         [(podman-auth ctx) "/kaniko/.docker/config.json"]]})})

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
                (assoc "MONKEYCI_VERSION" (lib-version ctx)))]
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
  (-> (shadow-release "test-gui" :test/node)
      ;; Explicitly run the tests, since :autorun always return zero
      (update :script conj "node target/js/node.js")))

(def build-gui-release
  (shadow-release "release-gui" :frontend))

(defn oci-config-file [{:keys [checkout-dir]}]
  (io/file checkout-dir "oci-config"))

(defn oci-creds
  "Creates oci credentials file"
  [ctx]
  (let [config (oci-config-file ctx)]
    ;; Write the config to a file, that will be mounted in the container
    (shell/param-to-file ctx "oci-config" config)
    (fs/set-posix-file-permissions config "rw-------")
    (fs/delete-on-exit config)
    core/success))

(defn upload-app-artifact
  "If this is a release build, uploads the uberjar to the OCI artifact registry."
  [ctx]
  (when (release? ctx)
    (let [repo-ocid (-> (api/build-params ctx)
                        (get "repo-ocid"))]
      {:container/image "ghcr.io/oracle/oci-cli:latest"
       :script ["cd app" ; FIXME Use work-dir instead, but's relative to the script dir
                (str "oci artifacts generic artifact upload-by-path"
                     " --repository-id=" repo-ocid
                     " --artifact-path=monkeyci/app/monkeyci.jar"
                     " --artifact-version=" (tag-version ctx)
                     " --content-body=target/monkeyci-standalone.jar")]
       :container/mounts [[(oci-config-file ctx) "/oracle/.oci/config"]]})))

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
   oci-creds
   build-app-image
   build-gui-image
   upload-app-artifact])

;; Unused
#_(core/defpipeline braid-bot
    [bot-uberjar
     image-creds
     build-bot-image])

;; Return the pipelines
(defn all-pipelines [ctx]
  (cond-> [test-all]
    ;; Optionally publish images and libs
    (should-publish? ctx)
    (concat [publish-libs
             publish-images])))
