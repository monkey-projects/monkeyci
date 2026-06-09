;; Build script for Monkey-ci itself
(ns build
  (:require [clojars :as clojars]
            [clojure.string :as cs]
            [config :as config]
            [medley.core :as mc]
            [minio :as minio]
            [monkey.ci.api :as m]
            [monkey.ci.build.api :as api]
            [monkey.ci.ext.junit]
            [monkey.ci.plugin
             [github :as gh]
             [kaniko :as kaniko]
             [pushover :as po]]
            [predicates :as p]))

(defn clj-container 
  "Executes script in clojure container"
  [id dir & args]
  (-> (m/container-job id)
      ;; Alpine based images don't exist for arm, so use debian
      (m/image config/clj-image)
      (m/script
       [(str "cd " dir
             " && "
             (cs/join " " (concat ["clojure" "-Sdeps" "'{:mvn/local-repo \"../m2\"}'"] args)))])
      (m/caches [(m/cache (str id "-mvn-repo") "m2")])))

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
                          :should-test? p/build-app?})
          (update :save-artifacts conj (coverage-artifact "app"))
          (cond->
            (p/build-common? ctx) (m/depends-on ["test-common"]))))

(defn- test-sublib [{:keys [dir id should?]}]
  (let [id (or id (str "test-" dir))]
    (fn [ctx]
      (run-tests ctx {:id id :dir dir :should-test? should?}))))

(defn test-test-lib [ctx]
  (cond-> ((test-sublib {:dir "test-lib" :should? p/build-test-lib?}) ctx)
    (p/publish-script? ctx) (m/depends-on "publish-script")))

(def test-common
  (test-sublib {:dir "common" :should? p/build-common?}))

(def test-core
  (test-sublib {:dir "core" :should? p/build-core?}))

(def test-script
  (test-sublib {:dir "script" :should? p/build-script?}))

(def test-cli
  (test-sublib {:dir "cli" :should? p/build-cli?}))

(def uberjar-artifact
  (m/artifact "uberjar" "app/target/monkeyci-standalone.jar"))

(defn app-uberjar [ctx]
  (when (p/publish-app? ctx)
    (let [v (config/tag-version ctx)]
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
  (when (p/publish-app? ctx)
    (-> (m/action-job
         "upload-uberjar"
         (fn [ctx]
           (upload-to-bucket
            ctx
            #(minio/put-s3-file %1 %2
                                (format "monkeyci/%s.jar" (config/image-version ctx))
                                (m/in-work ctx (:path uberjar-artifact))
                                {"env" (if (p/release? ctx) "prod" "staging")}))))
        (m/depends-on ["app-uberjar"])
        (m/restore-artifacts [(m/dir-artifact uberjar-artifact)]))))

(defn prepare-install-script
  "Prepares the cli install script by replacing the version.  Returns the
   script contents."
  [ctx]
  (cs/replace (slurp (m/in-work ctx "app/dev-resources/install-cli.sh"))
              "{{version}}" (config/tag-version ctx)))

(defn upload-install-script [ctx]
  (when (p/release? ctx)
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

(defn build-app-image [ctx]
  (when (p/publish-app? ctx)
    (kaniko/multi-platform-image
     {:dockerfile "docker/Dockerfile"
      :archs (config/archs ctx)
      :target-img (config/app-image ctx)
      :image
      {:job-id "publish-app-img"
       :container-opts
       {:restore-artifacts [(m/dir-artifact uberjar-artifact)]
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
  (when (p/publish-gui? ctx)
    (kaniko/multi-platform-image
     (gui-image-config "gui-img" config/gui-img (config/image-version ctx) (config/archs ctx))
     ctx)))

(defn- clojars-latest-version
  "Fetches latest version from the lib declared in the `deps.edn` in given dir."
  [dir ctx]
  (->> [(m/checkout-dir ctx) dir "deps.edn"]
       (cs/join "/")
       (clojars/extract-lib)
       (apply clojars/latest-version)))

(defn- publish-env
  "Creates clojars env vars for publishing a lib"
  [ctx version]
  (-> (api/build-params ctx)
      (select-keys ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"])
      (mc/assoc-some "MONKEYCI_VERSION" version)))

(defn publish 
  "Executes script in clojure container that has clojars publish env vars"
  [ctx id dir & [version]]
  (let [v (or version (config/tag-version ctx))]
    ;; If this is a release, and it's already been deployed, then skip this.
    ;; This is to be able to re-run a release build when a job down the road has
    ;; previously failed.
    (if (or (nil? v) (not= v (clojars-latest-version dir ctx)))
      (-> (clj-container id dir "-T:jar:deploy")
          (assoc :container/env (publish-env ctx v)))
      (m/action-job id (constantly (m/with-message m/success "Version was already published"))))))

(defn publish-app [ctx]
  (when (p/publish-app? ctx)
    (some-> (publish ctx "publish-app" "app")
            (m/depends-on (cond-> ["test-app"]
                            ;; TODO Since this job may also be excluded depending
                            ;; on clojars, this could still be incorrect
                            (p/publish-common? ctx) (conj "publish-common")
                            (p/publish-core? ctx) (conj "publish-core"))))))

(defn publish-test-lib [ctx]
  (when (p/publish-test-lib? ctx)
    ;; TODO Overwrite monkeyci version if necessary
    ;; (format "-Sdeps '{:override-deps {:mvn/version \"%s\"}}'" (config/tag-version ctx))
    (some-> (publish ctx "publish-test-lib" "test-lib")
            (m/depends-on ["test-test-lib"]))))

(defn publish-common [ctx]
  (when (p/publish-common? ctx)
    (some-> (publish ctx "publish-common" "common")
            (m/depends-on ["test-common"]))))

(defn publish-core [ctx]
  (when (p/publish-core? ctx)
    (some-> (publish ctx "publish-core" "core")
            (m/depends-on ["test-core"]))))

(defn publish-script [ctx]
  (when (p/publish-script? ctx)
    (some->
     (cond-> (publish ctx "publish-script" "script")
       true (m/depends-on ["test-script"])
       (p/publish-core? ctx) (m/depends-on ["test-core"])))))

(def github-release
  "Creates a release in github"
  (gh/release-job {:dependencies ["publish-app"]}))

(defn- npm-job [id script]
  (-> (m/container-job id)
      (m/image config/node-image)
      (m/work-dir "gui")
      (m/script (into ["npm install"] script))
      (m/caches (m/cache "node-modules" "node_modules"))))

(defn- shadow-release [id & builds]
  (-> (npm-job id
               [(str "clojure -Sdeps '{:mvn/local-repo \".m2\"}' -M:test -m shadow.cljs.devtools.cli release "
                     (cs/join " " builds))])
      (m/caches (m/cache "mvn-gui-repo" ".m2"))))

(defn test-gui [ctx]
  (when (p/build-gui? ctx)
    (let [art-id "junit-gui"
          junit "junit.xml"]
      (-> (shadow-release "test-gui" :test/node)
          ;; Explicitly run the tests, since :autorun always returns zero
          (update :script conj (str "node target/js/node.js > " junit))
          (assoc :save-artifacts [(m/artifact art-id junit)]
                 :junit {:artifact-id art-id
                         :path junit})))))

(defn- override-gui-version
  "Overrides the shadow build version with the specified one in the script.  This assumes
   the command to modify is the last one."
  [script v]
  (concat [(first script)
           (str (second script)
                (format " --config-merge '{:compiler-options {:closure-defines {monkey.ci.gui.version/VERSION \"%s\"}}}'" v))]
          (drop 2 script)))

(defn build-gui-release [ctx]
  (when (p/publish-gui? ctx)
    (cond->
        (-> (shadow-release "release-gui" :frontend :dashboard)
            (m/depends-on ["test-gui"])
            ;; Also generate index pages for app and admin sites
            (update :script concat [(format "clojure -X%s:gen-pages" (if (p/release? ctx) "" ":staging"))
                                    "npm run postcss:release"])
            (assoc :save-artifacts [gui-release-artifact]))
        (p/release? ctx) (update :script override-gui-version (config/tag-version ctx)))))

(def cli-uberjar-artifact (m/artifact "cli-uberjar" "cli/target/monkeyci-cli.jar"))

(defn cli-uberjar [ctx]
  (when (p/publish-cli? ctx)
    (-> (clj-container "cli-uberjar" "cli" "-T:uberjar")
        (m/depends-on "test-cli")
        (m/save-artifacts [cli-uberjar-artifact]))))

(defn cli-native-img [ctx]
  (when (p/publish-cli? ctx)
    (let [uberjar "target/monkeyci-cli.jar"]
      (-> (m/container-job "cli-native-img")
          (m/image "ghcr.io/graalvm/native-image-community:25")
          (m/work-dir "cli")
          (m/script [(cs/join " "
                              ["native-image"
                               "-cp" uberjar
                               "-jar" uberjar
                               "-J-Dclojure.spec.skip.macros=true"
                               "-J-Dclojure.compiler.direct-linking=true"
                               "--initialize-at-build-time"
                               "--no-fallback"
                               "-march=compatibility"
                               "monkeyci"])])
          (m/size 3) ; Process requires lots of memory
          (m/depends-on "cli-uberjar")
          (m/restore-artifacts [(m/artifact "cli-uberjar" "target")])
          (m/save-artifacts [(m/artifact "native-cli" "monkeyci")])))))

(defn scw-images
  "Generates a job that patches the scw-images repo in order to build a new
   Scaleway-specific image, using the version associated with this build."
  [ctx]
  (let [v (config/image-version ctx)
        patches (->> [{:dir "api"
                       :dep "app-img-manifest"
                       :checker p/publish-app?}
                      {:dir "gui"
                       :dep "gui-img-manifest"
                       :checker p/publish-gui?}]
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
  (when (p/release? ctx)
    (po/pushover-msg
     {:msg (str "MonkeyCI version " (config/tag-version ctx) " has been released.")
      :dependencies ["app-img-manifest" "gui-img-manifest"]})))

;; List of jobs
(def jobs
  [test-common
   test-core
   test-app
   test-gui
   test-test-lib
   test-script
           
   app-uberjar
   upload-uberjar
   upload-install-script
   publish-common
   publish-app
   publish-test-lib
   publish-core
   publish-script
   github-release

   ;; Base images
   build-gui-release
   build-app-image
   build-gui-image

   ;; CLI
   test-cli
   cli-uberjar
   cli-native-img
   
   ;; Trigger scaleway images
   scw-images
   
   notify])
