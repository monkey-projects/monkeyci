(ns monkey.ci.docker
  (:require [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.walk :as cw]
            [contajners.core :as c]
            [monkey.ci.step-runner :as sr])
  (:import org.apache.commons.io.IOUtils))

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

(defn inspect-container
  [client id]
  (c/invoke client {:op :ContainerInspect
                    :params {:id id}}))

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
  [client id & [opts]]
  (c/invoke client {:op :ContainerLogs
                    :params (merge {:id id
                                    :follow true
                                    :stdout true}
                                   opts)
                    :as :stream}))

(def stream-types [:stdin :stdout :stderr])

(defn- arr->int
  "Given a char seq that is actually a byte array, converts it to an integer"
  [arr]
  (reduce (fn [r b]
            (+ (* 0x100 r) (mod (+ 0x100 (int b)) 0x100)))
          0
          arr))

(defn- read-into-buf
  "Reads up to the buffer size bytes from the stream.  Returns the buffer,
   or `nil` if EOF was reached before the buffer was filled up."
  [s buf]
  (when (= (IOUtils/read s buf) (count buf))
    buf))

(defn- read-exactly
  "Reads exactly `n` bytes from input stream, or `nil` if EOF was reached
   before that."
  [in n]
  (read-into-buf in (byte-array n)))

(defn- parse-next-line
  "Parses the next line from the input stream, or returns `nil` if the
   stream is at an end.  Closes the stream if no more information could
   be read."
  [in]
  (let [buf (byte-array 4)
        read-type (fn []
                    (let [st (some->> (read-into-buf in buf)
                                      (first)
                                      (int)
                                      (get stream-types))]
                      (if (every? zero? (->> buf (seq) (rest)))
                        st
                        (log/warn "Invalid header, expected all zeroes:" (seq buf)))))
        read-size (fn [t]
                    (when-let [s (some-> (read-into-buf in buf)
                                         (arr->int))]
                      {:stream-type t
                       :size s}))
        read-msg (fn [{:keys [size] :as r}]
                   (when-let [msg (some-> (read-exactly in size)
                                          (String.))]
                     (assoc r :message msg)))]
    ;; Either return the parsed line, or close the stream
    (or (some-> (read-type)
                (read-size)
                (read-msg))
        (.close in))))

(defn parse-log-stream
  "Given a Docker input stream, parses it in to lines and returns it as
   a lazy seq of parsed maps, containing the stream type, size and message."
  [s]
  (->> (repeatedly #(parse-next-line s))
       (take-while some?)))

(defn run-container
  "Utility function that creates and starts a container, and then reads the logs
   and returns them as a seq of strings."
  [c name config]
  (let [{id :Id :as co} (create-container c name config)]
    (start-container c id)
    (->> (container-logs c id)
         (parse-log-stream)
         (map (comp (memfn trim) :message)))))

(defrecord DockerConfig [config]
  sr/StepRunner
  (run-step [this ctx]
    :todo))
