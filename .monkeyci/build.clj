;; Build script for Monkey-ci itself
(require '[monkey.ci.build.core :as core])
(require '[monkey.ci.build.shell :as shell])
(require '[clojure.java.io :as io])
(require '[clojure.tools.logging :refer [info] :rename {info log}])
(require '[config.core :refer [env]])

(defn clj [& args]
  #_(apply shell/bash "clojure" args)
  (apply str "clojure " args))

(defn clj-container [dir script]
  "Executes script in clojure container"
  {:container/image "docker.io/clojure:temurin-20-tools-deps-alpine"
   :script (concat [(str "cd " dir)] script)})

(defn clj-dir
  "Runs `clojure` command in the given working dir"
  [dir & args]
  (clj-container dir [(apply clj args)]))

(def clj-lib (partial clj-dir "lib"))
(def clj-app (partial clj-dir "app"))

(def test-lib (clj-lib "-X:test:junit"))
(def test-app (clj-app "-M:test:junit"))

(def app-uberjar (clj-app "-X:jar:uber"))

(defn install-app
  "Installs the application in the user's home directory by copying the
   uberjar to ~/lib and generating a script in ~/bin"
  [{:keys [work-dir]}]
  (let [lib (io/file shell/home "lib/monkeyci")
        dest (io/file lib "monkeyci.jar")]
    (log "Installing application to" lib)
    (if (or (.exists lib) (true? (.mkdirs lib)))
      (do
        ;; Copy the uberjar
        (io/copy (io/file work-dir "app/target/monkeyci-standalone.jar") dest)
        ;; Generate script
        (let [script (io/file shell/home "bin/monkeyci")]
          (log "Generating script at" script)
          (spit script (format "#!/bin/sh\njava -jar %s $*\n" (.getCanonicalPath dest)))
          (.setExecutable script true))
        core/success)
      core/failure)))

(def docker-image
  (shell/bash "docker" "build" "-t" "monkeyci" "-f" "docker/Dockerfile" "."))

(defn set-name [s n]
  (assoc s :name n))

(def test-pipeline
  (core/pipeline
   {:name "test"
    :steps [(set-name test-lib "test-lib")
            (set-name test-app "test-app")]}))

(def install-pipeline
  (core/pipeline
   {:name "install"
    :steps [app-uberjar
            install-app
            docker-image]}))

;; Return the pipelines
[test-pipeline
 #_install-pipeline]
