;; Build script for Monkey-ci itself
(require '[monkey.ci.build.core :as core])
(require '[monkey.ci.build.shell :as shell])
(require '[clojure.java.io :as io])
(require '[clojure.tools.logging :refer [info] :rename {info log}])

(defn clj [& args]
  (apply shell/bash "clojure" args))

(defn clj-dir
  "Runs `clojure` command in the given working dir"
  [dir & args]
  {:work-dir dir
   :action (apply clj args)})

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

;; Return the pipelines
[(core/pipeline
  {:name "test"
   :steps [test-lib
           #_{:container/image "clojure:temurin-20-tools-deps-alpine"
            :script ["clojure -X:test:junit"]
            :action (constantly "unused")
            :work-dir "lib"}
           test-app]})

 (core/pipeline
  {:name "install"
   :steps [app-uberjar
           install-app
           docker-image]})]
