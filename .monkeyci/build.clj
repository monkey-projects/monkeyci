;; Build script for Monkey-ci itself
(ns monkeyci.build.script
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [monkey.ci.build
             [api :as api]
             [core :as core]
             [shell :as shell]]
            [monkey.ci.ext.junit]
            [monkey.ci.plugin
             [github :as gh]
             [infra :as infra]]))

;; Version assigned when building main branch
;; TODO Determine automatically
(def snapshot-version "0.7.2-SNAPSHOT")

(def tag-regex #"^refs/tags/(\d+\.\d+\.\d+(\.\d+)?$)")

(defn ref?
  "Returns a predicate that checks if the ref matches the given regex"
  [re]
  #(core/ref-regex % re))

;; TODO Also run jobs if triggered from the api, in which case no files are touched

(def release?
  (ref? tag-regex))

(def api-trigger?
  (comp (partial = :api)
        core/trigger-src))

(def should-publish?
  (some-fn core/main-branch? release?))

(defn- dir-changed?
  "True if files have been touched for the given regex, or the 
   build was triggered from the api."
  [ctx re]
  (or (core/touched? ctx re)
      (api-trigger? ctx)))

(defn app-changed? [ctx]
  (dir-changed? ctx #"^app/.*"))

(defn gui-changed? [ctx]
  (dir-changed? ctx #"^gui/.*"))

(defn common-changed? [ctx]
  (dir-changed? ctx #"^common/.*"))

(def build-app? (some-fn app-changed? release?))
(def build-gui? (some-fn gui-changed? release?))
(def build-common? (some-fn common-changed? release?))

(def publish-app? (some-fn (every-pred app-changed? should-publish?) release?))
(def publish-gui? (some-fn (every-pred gui-changed? should-publish?) release?))
(def publish-common? (some-fn (every-pred common-changed? should-publish?) release?))

(defn tag-version
  "Extracts the version from the tag"
  [ctx]
  (some->> (core/git-ref ctx)
           (re-matches tag-regex)
           (second)))

(defn image-version
  "Retrieves image version from the tag, or the build id if this is the main branch."
  [ctx]
  ;; Prefix prod images with "release" for image retention policies
  (or (some->> (tag-version ctx) (str "release-"))
      (get-in ctx [:build :build-id])
      ;; Fallback
      "latest"))

(defn lib-version
  "Retrieves lib/jar version from the tag, or the next snapshot if this is the main branch."
  [ctx]
  (or (tag-version ctx)
      snapshot-version))

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

(defn- junit-artifact [dir]
  {:id (str dir "-junit")
   :path (str dir "/junit.xml")})

(defn coverage-artifact [dir]
  {:id (str dir "-coverage")
   :path (str dir "/target/coverage")})

(defn test-app [ctx]
  (let [junit-artifact (junit-artifact "app")]
    (when (build-app? ctx)
      (-> (clj-container "test-app" "app" "-M:test:junit:coverage")
          (assoc :save-artifacts [junit-artifact
                                  (coverage-artifact "app")]
                 :junit {:artifact-id (:id junit-artifact)
                         :path "junit.xml"})))))

(defn test-common [ctx]
  (let [junit-artifact (junit-artifact "common")]
    (when (build-common? ctx)
      (-> (clj-container "test-common" "common" "-X:test:junit")
          (assoc :save-artifacts [junit-artifact]
                 :junit {:artifact-id (:id junit-artifact)
                         :path "junit.xml"})))))

(def uberjar-artifact
  {:id "uberjar"
   :path "app/target/monkeyci-standalone.jar"})

(defn app-uberjar [ctx]
  (when (publish-app? ctx)
    (-> (clj-container "app-uberjar" "app" "-X:jar:uber")
        (assoc 
         :container/env {"MONKEYCI_VERSION" (lib-version ctx)}
         :save-artifacts [uberjar-artifact])
        (core/depends-on ["test-app"]))))

(def image-creds-artifact
  {:id "image-creds"
   ;; File must be called config.json for kaniko
   :path ".docker/config.json"})

;; Full path to the docker config file, used to push images
(defn img-repo-auth [ctx]
  (shell/in-work ctx (:path image-creds-artifact)))

(defn create-image-creds
  "Fetches credentials from the params and writes them to Docker `config.json`"
  [ctx]
  (let [auth-file (img-repo-auth ctx)]
    (println "Writing docker credentials to" auth-file)
    (when-not (fs/exists? auth-file)
      (shell/param-to-file ctx "dockerhub-creds" auth-file))))

(defn image-creds [ctx]
  (when (should-publish? ctx)
    (core/action-job
     "image-creds"
     create-image-creds
     {:save-artifacts [image-creds-artifact]})))

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
    (let [wd (shell/container-work-dir ctx)
          ctx-dir (cond-> wd 
                    context (str "/" context))]
      (core/container-job
       id
       (merge
        {:image "docker.io/monkeyci/kaniko:1.23.2"
         :script [(format "/kaniko/executor --dockerfile %s --destination %s --context dir://%s"
                          (str ctx-dir "/" (or dockerfile "Dockerfile"))
                          (str image ":" (or tag (image-version ctx)))
                          ctx-dir)]
         ;; Set docker config credentials location
         :container/env {"DOCKER_CONFIG" (str wd "/.docker")}
         :restore-artifacts [image-creds-artifact]
         :dependencies ["image-creds"]}
        opts)))))

(defn build-app-image [ctx]
  (when (publish-app? ctx)
    (kaniko-build-img
     {:id "publish-app-img"
      :dockerfile "docker/Dockerfile"
      :image app-img
      :opts {:restore-artifacts [uberjar-artifact image-creds-artifact]
             :dependencies ["image-creds" "app-uberjar"]}})))

(def gui-release-artifact
  {:id "gui-release"
   :path "resources/public/js"})

(defn build-gui-image [ctx]
  (when (publish-gui? ctx)
    (kaniko-build-img
     {:id "publish-gui-img"
      :context "gui"
      :image gui-img
      :memory 3 ;GB
      ;; Restore artifacts but modify the path because work dir is not the same
      :opts {:restore-artifacts [(update gui-release-artifact :path (partial str "gui/"))
                                 image-creds-artifact]
             :dependencies ["image-creds" "release-gui"]}})))

(defn publish [ctx id dir]
  "Executes script in clojure container that has clojars publish env vars"
  (when (publish-app? ctx)
    (let [env (-> (api/build-params ctx)
                  (select-keys ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"])
                  (assoc "MONKEYCI_VERSION" (lib-version ctx)))]
      (-> (clj-container id dir
                         "-X:jar:deploy")
          (assoc :container/env env)))))

(defn publish-app [ctx]
  ;; App is dependent on the common lib, so we should replace version here
  ;; (format "-Sdeps '{:override-deps {:mvn/version \"%s\"}}'" (lib-version ctx))
  (some-> (publish ctx "publish-app" "app")
          (core/depends-on ["test-app"])))

(defn publish-common [ctx]
  (some-> (publish ctx "publish-common" "common")
          (core/depends-on ["test-common"])))

(def github-release
  "Creates a release in github"
  (gh/release-job {:dependencies ["publish-app"]}))

(defn- shadow-release [id build]
  (core/container-job
   id
   {:image "docker.io/monkeyci/clojure-node:1.11.4"
    :work-dir "gui"
    :script ["npm install"
             (str "clojure -Sdeps '{:mvn/local-repo \".m2\"}' -M:test -m shadow.cljs.devtools.cli release " build)]
    :caches [{:id "mvn-gui-repo"
              :path ".m2"}
             {:id "node-modules"
              :path "node_modules"}]}))

(defn test-gui [ctx]
  (when (build-gui? ctx)
    (let [art-id "junit-gui"
          junit "junit.xml"]
      (-> (shadow-release "test-gui" :test/node)
          ;; Explicitly run the tests, since :autorun always returns zero
          (update :script conj (str "node target/js/node.js > " junit))
          (assoc :save-artifacts [{:id art-id
                                   :path junit}]
                 :junit {:artifact-id art-id
                         :path junit})))))

(defn build-gui-release [ctx]
  (when (publish-gui? ctx)
    (-> (shadow-release "release-gui" :frontend)
        (core/depends-on ["test-gui"])
        (assoc :save-artifacts [gui-release-artifact]))))

(defn deploy
  "Job that auto-deploys the image to staging by pushing the new image tag to infra repo."
  [ctx]
  (let [images (->> (zipmap ["monkeyci-api" "monkeyci-gui"]
                            ((juxt publish-app? publish-gui?) ctx))
                    (filter (comp true? second))
                    (map (fn [[img _]]
                           [img (image-version ctx)]))
                    (into {}))]
    (when (and (should-publish? ctx)
               (not (release? ctx))
               (not-empty images))
      (core/action-job
       "deploy"
       (fn [ctx]
         (if-let [token (get (api/build-params ctx) "github-token")]
           ;; Patch the kustomization file
           (if (infra/patch+commit! (infra/make-client token)
                                    :staging ; Only staging for now
                                    images)
             core/success
             (assoc core/failure :message "Unable to patch version in infra repo"))
           (assoc core/failure :message "No github token provided")))
       {:dependencies (->> [(when (publish-app? ctx) "publish-app-img")
                            (when (publish-gui? ctx) "publish-gui-img")]
                           (remove nil?))}))))

;; TODO Add jobs that auto-deploy to staging after running some sanity checks
;; We could do a git push with updated kustomization file.
;; But running sanity checks requires a running app.  Either we could rely on
;; argocd to do a blue/green deploy, or start it as a service (somehow) and
;; run the checks here.  The latter is preferred as it is contained inside the
;; build process.

;; List of jobs
[test-app
 app-uberjar
 publish-app
 github-release
 test-gui
 build-gui-release
 image-creds
 build-app-image
 build-gui-image
 deploy]
