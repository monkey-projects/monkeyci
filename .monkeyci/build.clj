;; Build script for Monkey-ci itself
(require '[monkey.ci.build.core :as core])
(require '[config.core :refer [env]])

(defn clj [& args]
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

(def container-image
  ;; TODO Credentials, must be mounted to /kaniko/.docker/config.json
  {:container/image "docker.io/bitnami/kaniko:latest"
   :container/cmd ["-d" "docker.io/monkeyci/app:latest" "-f" "docker/Dockerfile" "-c" "."]})

(defn set-name [s n]
  (assoc s :name n))

(def test-pipeline
  (core/pipeline
   {:name "test"
    :steps [(set-name test-lib "test-lib")
            (set-name test-app "test-app")]}))

(def publish-pipeline
  (core/pipeline
   {:name "publish"
    :steps [app-uberjar
            container-image]}))

;; Return the pipelines
[test-pipeline
 #_publish-pipeline]
