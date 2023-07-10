(ns monkey.ci.docker
  (:require [camel-snake-kebab.core :as csk]
            [clojure.walk :as cw]
            [contajners.core :as c]
            [monkey.ci.step-runner :as sr]))

(def default-conn {:uri "unix:///var/run/docker.sock"})
(def api-version "v1.41")

(defn make-client
  "Creates a client for given category using the specified connection settings"
  [category & [conn]]
  (c/client {:engine :docker
             :category category
             :version api-version
             :conn (or conn default-conn)}))

(defn- convert-body
  "Converts body to PascalCase as required by Docker api."
  [b]
  (cw/postwalk (fn [x]
                 (if (map-entry? x)
                   [(csk/->PascalCaseKeyword (first x)) (second x)]
                   x))
               b))

(defn create-container
  "Creates a container with given name and configuration.  `client` must
   be for category `:containers`."
  [client name config]
  (c/invoke client {:op :ContainerCreate
                    :params {:name name}
                    :data (convert-body config)}))

(defn start-container [client id]
  (c/invoke client {:op :ContainerStart
                    :params {:id id}}))

(defrecord DockerConfig [opts]
  sr/StepRunner
  (run-step [this ctx]
    :todo))
