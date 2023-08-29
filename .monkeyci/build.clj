;; Build script for Monkey-ci itself
(require '[monkey.ci.build.core :as core])
(require '[monkey.ci.build.shell :as shell])

(defn clj [& args]
  (apply shell/bash "clojure" args))

(defn clj-dir
  "Runs `clojure` command in the given working dir"
  [dir & args]
  {:work-dir dir
   :action (apply clj args)})

(def clj-lib (partial clj-dir "lib"))
(def clj-app (partial clj-dir "app"))

(def test-script (clj-lib "-X:test:junit"))
(def test-app (clj-app "-M:test:junit"))

(def app-uberjar (clj-app "-X:jar:uber"))

(defn install-app [ctx]
  (println "Installing application"))

;; Return the pipelines
[(core/pipeline
  {:name "build"
   :steps [test-script
           test-app]})

 (core/pipeline
  {:name "install"
   :steps [app-uberjar
           install-app]})]
