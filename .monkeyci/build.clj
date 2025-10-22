;; Build script for Monkey-ci itself
(ns build
  (:require [babashka.fs :as fs]
            [clojars :as clojars]
            [clojure.string :as cs]
            [medley.core :as mc]
            [minio :as minio]
            [monkey.ci.api :as m]
            [monkey.ci.build
             [api :as api]
             [core :as core]]
            [monkey.ci.ext.junit]
            [monkey.ci.plugin
             [github :as gh]
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
        m/source))

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

(defn common-changed? [ctx]
  (dir-changed? ctx #"^common/.*"))

(def build-app? (some-fn app-changed? release?))
(def build-gui? (some-fn gui-changed? release?))
(def build-test-lib? (some-fn test-lib-changed? release?))
(def build-common? (some-fn common-changed? release?))

(def publish-app? (some-fn (every-pred app-changed? should-publish?)
                           release?))
(def publish-gui? (some-fn (every-pred gui-changed? should-publish?)
                           release?))
(def publish-test-lib? (some-fn (every-pred test-lib-changed? should-publish?) release?))
(def publish-common? (some-fn (every-pred common-changed? should-publish?) release?))

(defn tag-version
  "Extracts the version from the tag"
  [ctx]
  (some->> (m/git-ref ctx)
           (re-matches tag-regex)
           (second)))

(defn image-version
  "Retrieves image version from the tag, or the build id if this is the main branch."
  [ctx]
  ;; Prefix prod images with "release" for image retention policies
  (or (some->> (tag-version ctx) (str "release-"))
      (m/build-id ctx)
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
      (m/caches [(m/cache "mvn-local-repo" "m2")])))

(defn- junit-artifact [dir]
  (m/artifact 
   (str dir "-junit")
   (str dir "/junit.xml")))

(defn coverage-artifact [dir]
  (m/artifact
   (str dir "-coverage")
   (str dir "/target/coverage")))

(defn- run-tests [ctx {:keys [id dir cmd should-test?] :or {cmd "-X:test:junit"}}]
  (let [junit-artifact (junit-artifact dir)]
    (when (should-test? ctx)
      (-> (clj-container id dir cmd)
          (assoc :save-artifacts [junit-artifact]
                 :junit {:artifact-id (:id junit-artifact)
                         :path "junit.xml"})))))

(defn test-app [ctx]
  (some-> (run-tests ctx {:id "test-app"
                          :dir "app"
                          :cmd "-M:test:coverage"
                          :should-test? build-app?})
          (update :save-artifacts conj (coverage-artifact "app"))
          (cond->
            (build-common? ctx) (m/depends-on ["publish-common"]))))

(defn test-test-lib [ctx]
  (run-tests ctx {:id "test-test-lib"
                  :dir "test-lib"
                  :should-test? build-test-lib?}))

(defn test-common [ctx]
  (run-tests ctx {:id "test-common"
                  :dir "common"
                  :should-test? build-common?}))

(def uberjar-artifact
  (m/artifact "uberjar" "app/target/monkeyci-standalone.jar"))

(defn- as-dir
  "Converts artifact that points to a file, to one that points to its parent
   directory."
  [art]
  (update art :path (comp str fs/parent)))

(defn app-uberjar [ctx]
  (when (publish-app? ctx)
    (let [v (tag-version ctx)]
      (cond-> (-> (clj-container "app-uberjar" "app" "-X:jar:uber")
                  (m/depends-on ["test-app"])
                  (m/save-artifacts uberjar-artifact))
        v (m/env {"MONKEYCI_VERSION" v})))))

(defn- upload-to-bucket [ctx do-put]
  (let [params (api/build-params ctx)
        url (get params "s3-url")
        access-key (get params "s3-access-key")
        secret-key (get params "s3-secret-key")
        client (minio/make-s3-client url access-key secret-key)
        bucket (get params "s3-bucket")]
    (if (some? (do-put client bucket))
      m/success
      m/failure)))

(defn upload-uberjar
  "Job that uploads the uberjar to configured s3 bucket.  From there it 
   will be downloaded by Ansible scripts."
  [ctx]
  (when (publish-app? ctx)
    (-> (m/action-job
         "upload-uberjar"
         (fn [ctx]
           (upload-to-bucket
            ctx
            #(minio/put-s3-file %1 %2
                                (format "monkeyci/%s.jar" (image-version ctx))
                                (m/in-work ctx (:path uberjar-artifact))))))
        (m/depends-on ["app-uberjar"])
        (m/restore-artifacts [(as-dir uberjar-artifact)]))))

(defn prepare-install-script
  "Prepares the cli install script by replacing the version.  Returns the
   script contents."
  [ctx]
  (cs/replace (slurp (m/in-work ctx "app/dev-resources/install-cli.sh"))
              "{{version}}" (tag-version ctx)))

(defn upload-install-script [ctx]
  (when (release? ctx)
    (-> (m/action-job
         "upload-install-script"
         (fn [ctx]
           (let [script (prepare-install-script ctx)]
             (upload-to-bucket
              ctx
              #(minio/put-s3-object %1 %2
                                    "install-cli.sh"
                                    (java.io.ByteArrayInputStream. (.getBytes script))
                                    (count script))))))
        (m/depends-on ["upload-uberjar"]))))

(def img-base "rg.fr-par.scw.cloud/monkeyci")
(def app-img (str img-base "/monkeyci-api"))
(def gui-img (str img-base "/monkeyci-gui"))

(defn oci-app-image [ctx]
  (str app-img ":" (image-version ctx)))

(defn archs [_]
  ;; Use fallback for safety
  #_(or (m/archs ctx) [:amd])
  ;; Using single arch for now.  When using a container agent, it may happen that
  ;; multiple builds run on the same agent but for different architectures, which may
  ;; mess up the result (e.g. amd container but actually arm arch)
  [:amd])

(defn build-app-image [ctx]
  (when (publish-app? ctx)
    (kaniko/multi-platform-image
     {:dockerfile "docker/Dockerfile"
      :archs (archs ctx)
      :target-img (oci-app-image ctx)
      :image
      {:job-id "publish-app-img"
       :container-opts
       {:restore-artifacts [(as-dir uberjar-artifact)]
        :dependencies ["app-uberjar"]}}
      :manifest
      {:job-id "app-img-manifest"}}
     ctx)))

(def gui-release-artifact
  (m/artifact "gui-release" "target"))

(defn- gui-image-config [id img version archs]
  {:subdir "gui"
   :dockerfile "Dockerfile"
   :target-img (str img ":" version)
   :archs archs
   :image
   {:job-id (str "publish-" id)
    :container-opts
    {:size 2
     ;; Restore artifacts but modify the path because work dir is not the same
     :restore-artifacts [(update gui-release-artifact :path (partial str "gui/"))]
     :dependencies ["release-gui"]}}
   :manifest
   {:job-id (str id "-manifest")}})

(defn build-gui-image [ctx]
  (when (publish-gui? ctx)
    (kaniko/multi-platform-image
     (gui-image-config "gui-img" gui-img (image-version ctx) (archs ctx))
     ctx)))

(defn publish 
  "Executes script in clojure container that has clojars publish env vars"
  [ctx id dir & [version]]
  (let [v (or version (tag-version ctx))]
    ;; If this is a release, and it's already been deployed, then skip this.
    ;; This is to be able to re-run a release build when a job down the road has
    ;; previously failed.
    (when-not (= v (->> [(m/checkout-dir ctx) dir "deps.edn"]
                        (cs/join "/")
                        (clojars/extract-lib)
                        (apply clojars/latest-version)))
      (let [env (-> (api/build-params ctx)
                    (select-keys ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"])
                    (mc/assoc-some "MONKEYCI_VERSION" v))]
        (-> (clj-container id dir "-X:jar:deploy")
            (assoc :container/env env))))))

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

(defn publish-common [ctx]
  (when (publish-common? ctx)
    (some-> (publish ctx "publish-common" "common")
            (m/depends-on ["test-common"]))))

(def github-release
  "Creates a release in github"
  (gh/release-job {:dependencies ["publish-app"]}))

(defn- shadow-release [id build]
  (-> (m/container-job id)
      (m/image "docker.io/monkeyci/clojure-node:1.12.3")
      (m/work-dir "gui")
      (m/script
       ["npm install"
        (str "clojure -Sdeps '{:mvn/local-repo \".m2\"}' -M:test -m shadow.cljs.devtools.cli release "
             build)])
      (m/caches [(m/cache "mvn-gui-repo" ".m2")
                 (m/cache "node-modules" "node_modules")])))

(defn test-gui [ctx]
  (when (build-gui? ctx)
    (let [art-id "junit-gui"
          junit "junit.xml"]
      (-> (shadow-release "test-gui" :test/node)
          ;; Explicitly run the tests, since :autorun always returns zero
          (update :script conj (str "node target/js/node.js > " junit))
          (assoc :save-artifacts [(m/artifact art-id junit)]
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

(defn scw-images
  "Generates a job that patches the scw-images repo in order to build a new
   Scaleway-specific image, using the version associated with this build."
  [ctx]
  (let [v (image-version ctx)
        patches (->> [{:dir "api"
                       :dep "app-img-manifest"
                       :checker publish-app?}
                      {:dir "gui"
                       :dep "gui-img-manifest"
                       :checker publish-gui?}]
                     (filter (fn [{:keys [checker]}]
                               (checker ctx)))
                     (map (fn [{:keys [dir dep]}]
                            {:dep dep
                             :path (str dir "/VERSION")
                             :patcher (constantly v)})))]
    (when (not-empty patches)
      (-> (gh/patch-job {:job-id "scw-images"
                         :org "monkey-projects"
                         :repo "scw-images"
                         :branch "main"
                         :patches (map #(dissoc % :dep) patches)
                         ;; TODO More meaningful message
                         :commit-msg (str "Updated images for " v)})
          (m/depends-on (map :dep patches))))))

(defn notify [ctx]
  (when (release? ctx)
    (po/pushover-msg
     {:msg (str "MonkeyCI version " (tag-version ctx) " has been released.")
      :dependencies ["app-img-manifest" "gui-img-manifest"]})))

;; List of jobs
(def jobs
  [test-common
   test-app
   test-gui
   test-test-lib
           
   app-uberjar
   upload-uberjar
   upload-install-script
   publish-common
   publish-app
   publish-test-lib
   github-release

   ;; Base images
   build-gui-release
   build-app-image
   build-gui-image
   
   ;; Trigger scaleway images
   scw-images
   
   notify])
