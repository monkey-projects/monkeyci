;; Build script for Monkey-ci itself
(ns build
  (:require [babashka.fs :as fs]
            [clojars :as clojars]
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
                          :should-test? p/build-app?})
          (update :save-artifacts conj (coverage-artifact "app"))
          (cond->
            (p/build-common? ctx) (m/depends-on ["test-common"]))))

(defn test-test-lib [ctx]
  (run-tests ctx {:id "test-test-lib"
                  :dir "test-lib"
                  :should-test? p/build-test-lib?}))

(defn test-common [ctx]
  (run-tests ctx {:id "test-common"
                  :dir "common"
                  :should-test? p/build-common?}))

(def uberjar-artifact
  (m/artifact "uberjar" "app/target/monkeyci-standalone.jar"))

(defn- as-dir
  "Converts artifact that points to a file, to one that points to its parent
   directory."
  [art]
  (update art :path (comp str fs/parent)))

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
                                (m/in-work ctx (:path uberjar-artifact))))))
        (m/depends-on ["app-uberjar"])
        (m/restore-artifacts [(as-dir uberjar-artifact)]))))

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
      :args (config/archs ctx)
      :target-img (config/app-image ctx)
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
  (when (p/publish-gui? ctx)
    (kaniko/multi-platform-image
     (gui-image-config "gui-img" config/gui-img (config/image-version ctx) (config/archs ctx))
     ctx)))

(defn publish 
  "Executes script in clojure container that has clojars publish env vars"
  [ctx id dir & [version]]
  (let [v (or version (config/tag-version ctx))]
    ;; If this is a release, and it's already been deployed, then skip this.
    ;; This is to be able to re-run a release build when a job down the road has
    ;; previously failed.
    (when (or (nil? v) (not= v (->> [(m/checkout-dir ctx) dir "deps.edn"]
                                    (cs/join "/")
                                    (clojars/extract-lib)
                                    (apply clojars/latest-version))))
      (let [env (-> (api/build-params ctx)
                    (select-keys ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"])
                    (mc/assoc-some "MONKEYCI_VERSION" v))]
        (-> (clj-container id dir "-X:jar:deploy")
            (assoc :container/env env))))))

(defn publish-app [ctx]
  (when (p/publish-app? ctx)
    (some-> (publish ctx "publish-app" "app")
            (m/depends-on (cond-> ["test-app"]
                            ;; TODO Since this job may also be excluded depending
                            ;; on clojars, this could still be incorrect
                            (p/publish-common? ctx) (conj "publish-common"))))))

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
  (when (p/build-gui? ctx)
    (let [art-id "junit-gui"
          junit "junit.xml"]
      (-> (shadow-release "test-gui" :test/node)
          ;; Explicitly run the tests, since :autorun always returns zero
          (update :script conj (str "node target/js/node.js > " junit))
          (assoc :save-artifacts [(m/artifact art-id junit)]
                 :junit {:artifact-id art-id
                         :path junit})))))

(defn- gen-idx [ctx type]
  (format "clojure -X%s:gen-%s" (if (p/release? ctx) "" ":staging") (name type)))

(defn build-gui-release [ctx]
  (when (p/publish-gui? ctx)
    (-> (shadow-release "release-gui" :frontend)
        (m/depends-on ["test-gui"])
        ;; Also generate index pages for app and admin sites
        (update :script (partial concat [(gen-idx ctx :main)
                                         (gen-idx ctx :admin)
                                         (gen-idx ctx :404)]))
        (assoc :save-artifacts [gui-release-artifact]))))

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
