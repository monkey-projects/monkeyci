(ns monkey.ci.docker
  (:require [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
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

(defn delete-container
  [client id]
  (c/invoke client {:op :ContainerDelete
                    :params {:id id}}))

(defn list-containers
  [client args]
  (c/invoke client {:op :ContainerList
                    :params args}))

(defn start-container
  "Starts the container, returns the output as a stream"
  [client id]
  (c/invoke client {:op :ContainerStart
                    :params {:id id}}))

(defn container-logs
  "Attaches to the container in order to read logs"
  [client id]
  (c/invoke client {:op :ContainerLogs
                    :params {:id id
                             :follow true
                             :stdout true}
                    :as :stream}))

(def stream-types [:stdin :stdout :stderr])

(defn- arr->int
  "Given a char seq that is actually a byte array, converts it to an integer"
  [arr]
  (reduce (fn [r b]
            (+ (* 16 r) (int b)))
          0
          arr))

(defn parse-log-line [l]
  (try
    (let [h (take 8 l)]
      {:stream-type (get stream-types (int (first h)))
       :size (arr->int (subs l 4 8))
       :message (subs l 8)})
    (catch Exception ex
      (log/warn "Failed to parse log line" l ex)
      ;; Fallback
      {:message l})))

(defn stream->lines [s]
  ;; FIXME Close the reader when done
  (-> (io/reader s)
      (line-seq)))

(defn run-container
  "Utility function that creates and starts a container, and then reads the logs
   and returns them as a seq of strings."
  [c name config]
  (let [{id :Id :as co} (create-container c name config)]
    (start-container c id)
    (->> (container-logs c id)
         (stream->lines)
         (map (comp :message parse-log-line)))))

(defrecord DockerConfig [config]
  sr/StepRunner
  (run-step [this ctx]
    :todo))
