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
      "0.3.4-SNAPSHOT"))

(defn clj-container [id dir & args]
  "Executes script in clojure container"
  (core/container-job
   id
   {;; Alpine based images don't exist for arm, so use debian
    :image "docker.io/clojure:temurin-21-tools-deps-bookworm-slim"
    :script [(str "cd " dir
                  " && "
                  (cs/join " " (concat ["clojure" "-Sdeps" "'{:mvn/local-repo \"../m2\"}'"] args)))]
    :caches [{:id "mvn-local-repo"
              :path "m2"}]}))

(def test-app (clj-container "test-app" "app" "-M:test:junit"))

(def uberjar-artifact
  {:id "uberjar"
   :path "app/target/monkeyci-standalone.jar"})

(defn app-uberjar [ctx]
  (when (should-publish? ctx)
    (-> (clj-container "app-uberjar" "app" "-X:jar:uber")
        (assoc 
         :container/env {"MONKEYCI_VERSION" (lib-version ctx)}
         :save-artifacts [uberjar-artifact])
        (core/depends-on ["test-app"]))))

(def image-creds-artifact
  {:id "image-creds"
   :path ".docker/config.json"})

;; Full path to the docker config file, used to push images
(defn img-repo-auth [ctx]
  (shell/in-work ctx (:path image-creds-artifact)))

(defn create-image-creds
  "Fetches credentials from the params and writes them to Docker `config.json`"
  [ctx]
  (let [auth-file (img-repo-auth ctx)]
    (when-not (fs/exists? auth-file)
      (shell/param-to-file ctx "dockerhub-creds" auth-file)
      core/success)))

(def image-creds
  (core/action-job
   "image-creds"
   create-image-creds
   {:save-artifacts [image-creds-artifact]}))

(def img-base "fra.ocir.io/frjdhmocn5qi")
(def app-img (str img-base "/monkeyci"))
(def gui-img (str img-base "/monkeyci-gui"))

(defn- make-context [ctx dir]
  (cond-> (:checkout-dir ctx)
    (some? dir) (str "/" dir)))

(defn kaniko-build-img
  "Creates a step that builds and uploads an image using kaniko"
  [{:keys [id dockerfile context image tag opts]}]
  (fn [ctx]
    (let [wd (shell/container-work-dir ctx)]
      (core/container-job
       id
       (merge
        {:image "docker.io/monkeyci/kaniko:1.21.0"
         :container/cmd ["/kaniko/executor"
                         "--dockerfile" (str wd "/" (or dockerfile "Dockerfile"))
                         "--destination" (str image ":" (or tag (image-version ctx)))
                         "--context" "dir://" wd]
         ;; Set docker config credentials location
         :container/env {"DOCKER_CONFIG" (str wd "/" (:path image-creds-artifact))}
         :restore-artifacts [image-creds-artifact]
         :dependencies ["image-creds"]}
        opts)))))

(def build-app-image
  (kaniko-build-img
   {:id "publish-app-img"
    :dockerfile "docker/Dockerfile"
    :image app-img
    :opts {:restore-artifacts [uberjar-artifact image-creds-artifact]
           :dependencies ["image-creds" "app-uberjar"]}}))

(def build-gui-image
  (kaniko-build-img
   {:id "publish-gui-img"
    :context "gui"
    :image gui-img
    :opts {:dependencies ["image-creds" "release-gui"]}}))

(defn publish [ctx id dir]
  "Executes script in clojure container that has clojars publish env vars"
  (when (should-publish? ctx)
    (let [env (-> (api/build-params ctx)
                  (select-keys ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"])
                  (assoc "MONKEYCI_VERSION" (lib-version ctx)))]
      (-> (clj-container id dir "-X:jar:deploy")
          (assoc :container/env env)))))

(defn publish-app [ctx]
  (some-> (publish ctx "publish-app" "app")
          (core/depends-on ["test-app"])))

(defn- shadow-release [id build]
  (core/container-job
   id
   {:image "docker.io/dormeur/clojure-node:1.11.1"
    :work-dir "gui"
    :script ["npm install"
             (str "clojure -Sdeps '{:mvn/local-repo \".m2\"}' -M:test -m shadow.cljs.devtools.cli release " build)]
    :caches [{:id "mvn-gui-repo"
              :path ".m2"}
             {:id "node-modules"
              :path "node_modules"}]}))

(def test-gui
  (-> (shadow-release "test-gui" :test/node)
      ;; Explicitly run the tests, since :autorun always returns zero
      (update :script conj "node target/js/node.js")))

(defn build-gui-release [ctx]
  (when (should-publish? ctx)
    (-> (shadow-release "release-gui" :frontend)
        (core/depends-on ["test-gui"]))))

;; List of jobs
[test-app
 app-uberjar
 publish-app
 test-gui
 build-gui-release
 image-creds
 build-app-image
 build-gui-image]
