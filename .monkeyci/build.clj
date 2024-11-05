;; Build script for Monkey-ci itself
(ns build
  (:require [clojure.string :as cs]
            [monkey.ci.build
             [api :as api]
             [core :as core]]
            [monkey.ci.ext.junit]
            [monkey.ci.plugin
             [github :as gh]
             [infra :as infra]
             [kaniko :as kaniko]
             [pushover :as po]]))

;; Version assigned when building main branch
;; TODO Determine automatically
(def snapshot-version "0.9.3-SNAPSHOT")

(def tag-regex #"^refs/tags/(\d+\.\d+\.\d+(\.\d+)?$)")

(defn ref?
  "Returns a predicate that checks if the ref matches the given regex"
  [re]
  #(core/ref-regex % re))

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

(def build-app? (some-fn app-changed? common-changed? release?))
(def build-gui? (some-fn gui-changed? release?))
(def build-common? (some-fn common-changed? release?))

(def publish-app? (some-fn (every-pred (some-fn app-changed? common-changed?)
                                       should-publish?)
                           release?))
(def publish-gui? (some-fn (every-pred gui-changed? should-publish?)
                           release?))
#_(def publish-common? (some-fn (every-pred common-changed? should-publish?) release?))

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

(defn clj-container 
  "Executes script in clojure container"
  [id dir & args]
  (core/container-job
   id
   { ;; Alpine based images don't exist for arm, so use debian
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

(def img-base "fra.ocir.io/frjdhmocn5qi")
(def app-img (str img-base "/monkeyci"))
(def gui-img (str img-base "/monkeyci-gui"))

(defn build-app-image [ctx]
  (when (publish-app? ctx)
    (kaniko/multi-platform-image
     {:dockerfile "docker/Dockerfile"
      :archs [:arm :amd]
      :target-img (str app-img ":" (image-version ctx))
      :image
      {:job-id "publish-app-img"
       :container-opts
       {:restore-artifacts [uberjar-artifact]
        :dependencies ["app-uberjar"]}}
      :manifest
      {:job-id "app-img-manifest"}}
     ctx)))

(def gui-release-artifact
  {:id "gui-release"
   :path "resources/public/js"})

(defn build-gui-image [ctx]
  (when (publish-gui? ctx)
    (kaniko/image
     {:job-id "publish-gui-img"
      :subdir "gui"
      :dockerfile "Dockerfile"
      :target-img (str gui-img ":" (image-version ctx))
      :container-opts
      {:memory 3 ;GB
       ;; Restore artifacts but modify the path because work dir is not the same
       :restore-artifacts [(update gui-release-artifact :path (partial str "gui/"))]
       :dependencies ["release-gui"]}}
     ctx)))

(defn publish 
  "Executes script in clojure container that has clojars publish env vars"
  [ctx id dir & [version]]
  (let [env (-> (api/build-params ctx)
                (select-keys ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"])
                (assoc "MONKEYCI_VERSION" (or version (lib-version ctx))))]
    (-> (clj-container id dir
                       "-X:jar:deploy")
        (assoc :container/env env))))

(defn publish-app [ctx]
  (when (publish-app? ctx)
    ;; App is dependent on the common lib, so we should replace version here
    ;; (format "-Sdeps '{:override-deps {:mvn/version \"%s\"}}'" (lib-version ctx))
    (some-> (publish ctx "publish-app" "app")
            (core/depends-on ["test-app"]))))

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
       {:dependencies (->> [(when (publish-app? ctx) "app-img-manifest")
                            (when (publish-gui? ctx) "publish-gui-img")]
                           (remove nil?))}))))

(defn notify [ctx]
  (when (release? ctx)
    (po/pushover-msg
     {:msg (str "MonkeyCI version " (tag-version ctx) " has been released.")
      :dependencies ["app-img-manifest" "publish-gui-img"]})))

;; TODO Add jobs that auto-deploy to staging after running some sanity checks
;; We could do a git push with updated kustomization file.
;; But running sanity checks requires a running app.  Either we could rely on
;; argocd to do a blue/green deploy, or start it as a service (somehow) and
;; run the checks here.  The latter is preferred as it is contained inside the
;; build process.

;; List of jobs
[test-app
 test-gui
 
 app-uberjar
 publish-app
 github-release
 
 build-gui-release
 build-app-image
 build-gui-image
 deploy
 notify]
