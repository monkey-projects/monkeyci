;; Build script for Monkey-ci itself
(require '[monkey.ci.build.core :as core])
(require '[monkey.ci.build.api :as api])
(require '[monkey.ci.build.shell :as shell])
(require '[clojure.java.io :as io])
(require '[config.core :refer [env]])
(require '[babashka.fs :as fs])

(defn set-name [s n]
  (assoc s :name n))

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

(def test-lib (set-name (clj-lib "-X:test:junit") "test-lib"))
(def test-app (set-name (clj-app "-M:test:junit") "test-app"))

(def app-uberjar (clj-app "-X:jar:uber"))

;; Full path to the docker config file, used to push images
(def docker-config (fs/expand-home "~/.docker/config.json"))

(defn dockerhub-creds
  "Fetches docker hub credentials from the params and writes them to Docker `config.json`"
  [ctx]
  (when-not (fs/exists? docker-config)
    (println "Writing dockerhub credentials")
    (shell/param-to-file ctx "dockerhub-creds" docker-config)))

(def container-image
  {:container/image "docker.io/bitnami/kaniko:latest"
   :container/cmd ["-d" "docker.io/dormeur/monkey-ci:latest" "-f" "docker/Dockerfile" "-c" "."]
   ;; Credentials, must be mounted to /kaniko/.docker/config.json
   :container/mounts [[(str docker-config) "/kaniko/.docker/config.json"]]})

(def test-pipeline
  (core/pipeline
   {:name "test"
    :steps [test-lib
            test-app]}))

(def publish-pipeline
  (core/pipeline
   {:name "publish"
    :steps [app-uberjar
            dockerhub-creds
            container-image]}))

;; Return the pipelines
[test-pipeline
 publish-pipeline]
