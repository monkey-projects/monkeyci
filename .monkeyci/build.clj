;; Build script for Monkey-ci itself
(require '[monkey.ci.build.core :as core])
(require '[monkey.ci.build.shell :as shell])

(defn clj [& args]
  (apply shell/bash "clojure" args))

(defn clj-dir
  "Runs `clojure` command in the given working dir"
  [dir args]
  {:work-dir dir
   :action (apply clj args)})

(defn clj-lib [& args]
  (clj-dir "lib" args))

(defn clj-app [& args]
  (clj-dir "app" args))

(def test-script (clj-lib "-X:test:junit"))
(def test-app (clj-app "-M:test:junit"))

;; Return the pipelines
(core/pipeline
 {:name "build"
  :steps [test-script
          test-app]})
