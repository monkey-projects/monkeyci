;; Build script for Monkey-ci itself
(ns build
  (:require [babashka.fs :as fs]
            [clojure.string :as cs]
            [medley.core :as mc]
            [monkey.ci.build
             [api :as api]
             [core :as core]
             [v2 :as m]]
            [monkey.ci.ext.junit]
            [monkey.ci.plugin
             [github :as gh]
             [infra :as infra]
             [kaniko :as kaniko]
             [pushover :as po]]))

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
  (some-fn m/main-branch? release?))

(defn- dir-changed?
  "True if files have been touched for the given regex, or the 
   build was triggered from the api."
  [ctx re]
  (or (m/touched? ctx re)      
      (api-trigger? ctx)))

(defn app-changed? [ctx]
  (dir-changed? ctx #"^app/.*"))

(defn gui-changed? [ctx]
  (dir-changed? ctx #"^gui/.*"))

(defn test-lib-changed? [ctx]
  (dir-changed? ctx #"^test-lib/.*"))

(def build-app? (some-fn app-changed? release?))
(def build-gui? (some-fn gui-changed? release?))
(def build-test-lib? (some-fn test-lib-changed? release?))

(def publish-app? (some-fn (every-pred app-changed? should-publish?)
                           release?))
(def publish-gui? (some-fn (every-pred gui-changed? should-publish?)
                           release?))
(def publish-test-lib? (some-fn (every-pred test-lib-changed? should-publish?) release?))

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

(defn clj-container 
  "Executes script in clojure container"
  [id dir & args]
  (-> (m/container-job id)
      ;; Alpine based images don't exist for arm, so use debian
      (m/image "docker.io/clojure:temurin-21-tools-deps-bookworm-slim")
      (m/script
       [(str "cd " dir
             " && "
             (cs/join " " (concat ["clojure" "-Sdeps" "'{:mvn/local-repo \"../m2\"}'"] args)))])
      (m/caches [{:id "mvn-local-repo"
                  :path "m2"}])))

(defn- junit-artifact [dir]
  {:id (str dir "-junit")
   :path (str dir "/junit.xml")})

(defn coverage-artifact [dir]
  {:id (str dir "-coverage")
   :path (str dir "/target/coverage")})

(defn test-app [ctx]
  (let [junit-artifact (junit-artifact "app")]
    (when (build-app? ctx)
      ;; Disabled coverage because of spec gen errors
      (-> (clj-container "test-app" "app" "-M:test:junit")
          (assoc :save-artifacts [junit-artifact
                                  #_(coverage-artifact "app")]
                 :junit {:artifact-id (:id junit-artifact)
                         :path "junit.xml"})))))

(defn test-test-lib [ctx]
  (let [junit-artifact (junit-artifact "test-lib")]
    (when (build-test-lib? ctx)
      (-> (clj-container "test-test-lib" "test-lib" "-X:test:junit")
          (assoc :save-artifacts [junit-artifact]
                 :junit {:artifact-id (:id junit-artifact)
                         :path "junit.xml"})))))

(defn app-uberjar [ctx]
  (when (publish-app? ctx)
    (let [v (tag-version ctx)]
      (-> (clj-container "app-uberjar" "app" "-X:jar:uber")
          (assoc 
           :container/env (when v {"MONKEYCI_VERSION" v})
           :save-artifacts [{:id "uberjar"
                             :path "app/target/monkeyci-standalone.jar"}])
          (m/depends-on ["test-app"])))))

(def img-base "fra.ocir.io/frjdhmocn5qi")
(def app-img (str img-base "/monkeyci"))
(def gui-img (str img-base "/monkeyci-gui"))

(def scw-img-base "rg.fr-par.scw.cloud/monkeyci/")
(def scw-api-img (str scw-img-base "monkeyci"))
(def scw-gui-img (str scw-img-base "monkeyci-gui"))

;; Disabled arm because no compute capacity
(def archs [#_:arm :amd])

(defn oci-app-image [ctx]
  (str app-img ":" (image-version ctx)))

(defn build-app-image [ctx]
  (when (publish-app? ctx)
    (kaniko/multi-platform-image
     {:dockerfile "docker/Dockerfile"
      :archs archs
      :target-img (oci-app-image ctx)
      :image
      {:job-id "publish-app-img"
       :container-opts
       {:restore-artifacts [{:id "uberjar"
                             :path "app/target"}]
        :dependencies ["app-uberjar"]}}
      :manifest
      {:job-id "app-img-manifest"}}
     ctx)))

(def gui-release-artifact
  (m/artifact "gui-release" "target"))

(def scw-gui-config-artifact
  (m/artifact "scw-gui-config" "gui/resources/public/conf"))

(def scw-api-config-artifact
  (m/artifact "scw-api-config" "scw-api"))

(defn- gui-image-config [id img version]
  {:subdir "gui"
   :dockerfile "Dockerfile"
   :target-img (str img ":" version)
   :archs archs
   :image
   {:job-id (str "publish-" id)
    :container-opts
    {:memory 3                          ;GB
     ;; Restore artifacts but modify the path because work dir is not the same
     :restore-artifacts [(update gui-release-artifact :path (partial str "gui/"))]
     :dependencies ["release-gui"]}}
   :manifest
   {:job-id (str id "-manifest")}})

(defn build-gui-image [ctx]
  (when (publish-gui? ctx)
    (kaniko/multi-platform-image
     (gui-image-config "gui-img" gui-img (image-version ctx))
     ctx)))

(defn build-scw-gui-image [ctx]
  (when (publish-gui? ctx)
    (kaniko/multi-platform-image
     (-> (gui-image-config "scw-gui-img" scw-gui-img (image-version ctx))
         (assoc :creds-param "docker-scw-credentials")
         (update-in [:image :container-opts :dependencies] conj "prepare-scw-gui-config")
         (update-in [:image :container-opts :restore-artifacts] conj scw-gui-config-artifact))
     ctx)))

(defn publish 
  "Executes script in clojure container that has clojars publish env vars"
  [ctx id dir & [version]]
  (let [env (-> (api/build-params ctx)
                (select-keys ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"])
                (mc/assoc-some "MONKEYCI_VERSION" (or version (tag-version ctx))))]
    (-> (clj-container id dir
                       "-X:jar:deploy")
        (assoc :container/env env))))

(defn publish-app [ctx]
  (when (publish-app? ctx)
    (some-> (publish ctx "publish-app" "app")
            (m/depends-on ["test-app"]))))

(defn publish-test-lib [ctx]
  (when (publish-test-lib? ctx)
    ;; TODO Overwrite monkeyci version if necessary
    ;; (format "-Sdeps '{:override-deps {:mvn/version \"%s\"}}'" (tag-version ctx))
    (some-> (publish ctx "publish-test-lib" "test-lib")
            (m/depends-on ["test-test-lib"]))))

(def github-release
  "Creates a release in github"
  (gh/release-job {:dependencies ["publish-app"]}))

(defn- shadow-release [id build]
  (-> (m/container-job id)
      (m/image "docker.io/monkeyci/clojure-node:1.11.4")
      (m/work-dir "gui")
      (m/script
       ["npm install"
        (str "clojure -Sdeps '{:mvn/local-repo \".m2\"}' -M:test -m shadow.cljs.devtools.cli release "
             build)])
      (m/caches [{:id "mvn-gui-repo"
                  :path ".m2"}
                 {:id "node-modules"
                  :path "node_modules"}])))

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

(defn- gen-idx [ctx type]
  (format "clojure -X%s:gen-%s" (if (release? ctx) "" ":staging") (name type)))

(defn build-gui-release [ctx]
  (when (publish-gui? ctx)
    (-> (shadow-release "release-gui" :frontend)
        (m/depends-on ["test-gui"])
        ;; Also generate index pages for app and admin sites
        (update :script (partial concat [(gen-idx ctx :main)
                                         (gen-idx ctx :admin)]))
        (assoc :save-artifacts [gui-release-artifact]))))

(defn- spit-contents [dest contents]
  (when contents
    (fs/create-dirs (fs/parent dest))
    (spit (str dest) contents)
    contents))

(defn- generate-gui-config [ctx]
  (let [p (api/build-params ctx)
        dir (m/in-work ctx "gui/resources/public/conf/")
        write-conf (fn [f k]
                     (let [c (get p k)
                           dest (str (fs/path dir f))]
                       (println "Writing config to file" dest ":" c)
                       (spit-contents dest c)))]
    (if (and (write-conf "config.js" "scw-gui-config")
             (write-conf "admin-config.js" "scw-gui-admin-config"))
      (println "Created config files in" dir)
      (m/with-message m/failure "No config written"))))

(defn prepare-scw-gui-config
  "Creates config files to be included in the Scaleway gui image.  This is necessary
   because Scaleway containers don't support mounting files, and using env vars is
   not easy with nginx."
  [ctx]
  (when (publish-gui? ctx)
    (-> (m/action-job
         "prepare-scw-gui-config"
         generate-gui-config)
        (m/save-artifacts [scw-gui-config-artifact]))))

(defn- generate-api-config [ctx]
  (let [dest (m/in-work ctx (:path scw-api-config-artifact))
        config-file "config.edn"
        config-path "/home/monkeyci/config/"]
    (println "Writing API config and Scaleway dockerfile to" dest)
    ;; Generate Dockerfile
    (spit-contents (fs/path dest "Dockerfile")
                   (format "FROM %s\nCMD [\"-c\", \"%s\", \"internal\", \"server\"]\nCOPY %s %s\n"
                           (oci-app-image ctx)
                           (str (fs/path config-path config-file))
                           config-file
                           config-path))
    ;; Write scw config
    (if (spit-contents (fs/path dest config-file)
                       (get (api/build-params ctx) "scw-api-config"))
      (println "Created Dockerfile and config file in" dest)
      (m/with-message m/failure "No api config available"))))

(defn prepare-scw-api-config
  "Creates config file to include in Scaleway api container.  We cannot easily
   pass it all in using env vars, because there is a 1000 char limit.  In any
   case we have to create a new container, because Scaleway containers also don't
   support command arguments."
  [ctx]
  (when (publish-app? ctx)
    (-> (m/action-job
         "prepare-scw-api-config"
         generate-api-config)
        (m/save-artifacts [scw-api-config-artifact]))))

(defn build-scw-api-image
  "Builds and pushes an image for Scaleway, that is based upon the OCI image
   but includes a configuration file and default command.  This because Scaleway
   containers don't (yet?) support custom commands and cannot mount configfiles."
  [ctx]
  (when (publish-app? ctx)
    (kaniko/image
     {:job-id "publish-scw-api-img"
      :target-img (str scw-api-img ":" (image-version ctx))
      :arch :amd
      :subdir (:path scw-api-config-artifact)
      :container-opts {:dependencies ["prepare-scw-api-config"
                                      "app-img-manifest"]
                       :restore-artifacts [scw-api-config-artifact]}
      :creds-param "docker-scw-credentials"}
     ctx)))

(defn deploy
  "Job that auto-deploys the image to staging by pushing the new image tag to infra repo."
  [ctx]
  (let [images (->> (zipmap ["monkeyci-api" "monkeyci-gui" "monkeyci-admin"]
                            ((juxt publish-app? publish-gui? publish-gui?) ctx))
                    (filter (comp true? second))
                    (map (fn [[img _]]
                           [img (image-version ctx)]))
                    (into {}))
        token (get (api/build-params ctx) "github-token")]
    (when (and (should-publish? ctx)
               (not (release? ctx))
               (not-empty images)
               token)
      (-> (m/action-job
           "deploy"
           (fn [ctx]
             ;; Patch the kustomization file
             (if (infra/patch+commit! (infra/make-client token)
                                      :staging ; Only staging for now
                                      images)
               m/success
               (-> m/failure (m/with-message "Unable to patch version in infra repo")))))
          (m/depends-on (->> [(when (publish-app? ctx) "app-img-manifest")
                              (when (publish-gui? ctx) "gui-img-manifest")]
                             (remove nil?)))))))

(defn notify [ctx]
  (when (release? ctx)
    (po/pushover-msg
     {:msg (str "MonkeyCI version " (tag-version ctx) " has been released.")
      :dependencies ["app-img-manifest" "gui-img-manifest"]})))

;; TODO Add jobs that auto-deploy to staging after running some sanity checks
;; We could do a git push with updated kustomization file.
;; But running sanity checks requires a running app.  Either we could rely on
;; argocd to do a blue/green deploy, or start it as a service (somehow) and
;; run the checks here.  The latter is preferred as it is contained inside the
;; build process.

;; List of jobs
(def jobs
  [test-app
   test-gui
   test-test-lib
           
   app-uberjar
   publish-app
   publish-test-lib
   github-release

   ;; OCI images
   build-gui-release
   build-app-image
   build-gui-image

   ;; Scaleway images
   prepare-scw-gui-config
   build-scw-gui-image
   prepare-scw-api-config
   build-scw-api-image
   
   deploy
   notify])
