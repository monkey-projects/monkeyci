(ns monkey.ci.docker
  (:require [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.walk :as cw]
            [contajners.core :as c]
            [medley.core :as mc]
            [monkey.ci.containers :as mcc])
  (:import org.apache.commons.io.IOUtils
           [java.io InputStreamReader PrintWriter PushbackReader]))

(def default-conn {:uri "unix:///var/run/docker.sock"})
(def api-version "v1.41")

(defn make-client
  "Creates a client for given category using the specified connection settings"
  [category & [conn]]
  (c/client {:engine :docker
             :category category
             :version api-version
             :conn (or conn default-conn)}))

(defn- convert-keys [obj f]
  (cw/postwalk (fn [x]
                 (if (map-entry? x)
                   [(f (first x)) (second x)]
                   x))
               obj))

(defn- ->pascal-case
  "Converts body to PascalCase as required by Docker api."
  [b]
  (convert-keys b csk/->PascalCaseKeyword))

(defn- ->kebab-case
  "Converts object to kebab-case."
  [b]
  (convert-keys b csk/->kebab-case-keyword))

(defn- invoke-and-convert [client & args]
  (-> (apply c/invoke client args)
      (->kebab-case)))

(defn pull-image
  "Pulls image from given url.  Requires a client for category `:images`."
  [client url]
  (invoke-and-convert client
                      {:op :ImageCreate
                       :params {:fromImage url}}))

(defn create-container
  "Creates a container with given name and configuration.  `client` must
   be for category `:containers`."
  [client name config]
  (invoke-and-convert client
                      {:op :ContainerCreate
                       :params {:name name}
                       :data (->pascal-case config)}))

(defn inspect-container
  [client id]
  (invoke-and-convert client
                      {:op :ContainerInspect
                       :params {:id id}}))

(defn delete-container
  [client id]
  (c/invoke client {:op :ContainerDelete
                    :params {:id id}}))

(defn list-containers
  [client args]
  (invoke-and-convert client
                      {:op :ContainerList
                       :params args}))

(defn start-container
  "Starts the container, returns the output as a stream"
  [client id]
  (invoke-and-convert client
                      {:op :ContainerStart
                       :params {:id id}}))

(defn stop-container
  "Stops the container"
  [client id]
  (c/invoke client
            {:op :ContainerStop
             :params {:id id}}))

(defn attach-container
  "Attaches to given container.  Returns a socket that can be used to communicate with it."
  [client id]
  (c/invoke client {:op :ContainerAttach
                    :params {:id id
                             :stream true
                             :stdin true
                             :stdout true
                             :stderr true}
                    :as :socket}))

(defn container-logs
  "Attaches to the container in order to read logs"
  [client id & [opts]]
  (c/invoke client {:op :ContainerLogs
                    :params (merge {:id id
                                    :follow true
                                    :stdout true}
                                   opts)
                    :as :stream
                    :throw-exceptions true}))

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
        (do
          (log/debug "Closing log input stream")
          (.close in)))))

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
  (let [{:keys [id] :as co} (create-container c name config)]
    (start-container c id)
    (->> (container-logs c id)
         (parse-log-stream)
         (map (comp (memfn trim) :message)))))

(defn ctx->container-config
  "Extracts all keys from the context step that have the `container` namespace,
   and drops that namespace."
  [ctx]
  (->> ctx
       :step
       (mc/filter-keys (comp (partial = "container") namespace))
       (mc/map-keys (comp keyword name))))

(defn make-docker-runner [config]
  (let [client (make-client :containers (get-in config [:env :docker-connection]))]
    (fn [ctx]
      (let [cn (str "build-" (random-uuid))
            conf (ctx->container-config ctx)
            cont (create-container client cn conf)]
        (log/debug "Container configuration:" conf)
        (log/info "Starting container" cn)
        (start-container client cn)
        cont))))

(defn read-until-match
  "Reads from the pushback reader until the given value is encountered.  Pushes
   back all the characters read after that.  Returns all characters read until
   the value was seen, or `nil` if the value has not been encountered before EOF."
  [^PushbackReader r p]
;;  (log/debug "Scanning for:" p)
  (let [buf (char-array (count p))]
    (loop [acc ""
           n (.read r buf)]
      (log/debug "Read" n "chars")
      (if (neg? n)
        ;; EOF reached
        nil
        ;; Otherwise some data was read, check if the value has been found
        (let [s (String. buf 0 n)
              all (str acc s)
              idx (.indexOf all p)]
          (if (neg? idx)
            ;; Value not encountered yet, try again
            (recur all (.read r buf))
            ;; Match found, push back any leftover chars and return
            (let [leftover (- (count all) idx (count p))
                  offs (- n leftover)]
              ;; FIXME Something's wrong here.  Leftover is always 4 even though the string is at an end.
              (when (and (pos? leftover) (< offs (count s)))
                (log/debug "Pushing back" leftover "chars from offset" offs ":"
                           (subs s offs (+ offs leftover)) "from" s)
                (.unread r buf offs leftover))
              (subs all 0 idx))))))))

(defmethod mcc/run-container :docker [ctx]
  (let [cn (str "build-" (random-uuid))
        job-id (get ctx :job-id (str (random-uuid)))
        prompt (str job-id "$")
        conn (get-in ctx [:env :docker-connection])
        client (make-client :containers conn)
        {:keys [image] :as conf} (merge (ctx->container-config ctx)
                                        {:cmd ["/bin/sh" "-e"]
                                         :open-stdin true
                                         :attach-stdin false
                                         :attach-stdout true
                                         :attach-stderr true
                                         :tty true
                                         ;; Specify custom prompt
                                         :env [(str "PS1=" prompt)]})
        print-logs (fn []
                     (->> (container-logs client cn)
                          (parse-log-stream)
                          (map (comp (memfn trim) :message))
                          (map (fn [l] (log/info l)))
                          (doall)))
        pull   (fn [{:keys [image]}]
                 (log/debug "Pulling image")
                 (:id (pull-image (make-client :images conn) image)))
        create (fn [_]
                 (log/debug "Creating container with configuration" conf)
                 (create-container client cn conf))
        start  (fn [{:keys [id] :as cont}]
                 (log/info "Starting container" cn "(id " id ")")
                 (when (start-container client id)
                   (log/debug "Container started")
                   cont))
        attach (fn [{:keys [id] :as cont}]
                 (log/debug "Attaching to container" id)
                 (some->> (attach-container client id)
                          (hash-map :container cont :socket)))
        wait-for-prompt (fn [r]
                          (log/debug "Waiting for prompt...")
                          ;; Wait for prompt but wrap in a future to allow for timeout
                          ;; For longer running tasks we'll need to increase this timeout.
                          (deref (future (read-until-match r prompt)) 10000 :timeout))
        execute (fn [{:keys [socket container] :as state}]
                  (let [script (get-in ctx [:step :script])
                        pw (PrintWriter. (.getOutputStream socket) true)
                        r (PushbackReader. (InputStreamReader. (.getInputStream socket)) (count prompt))
                        execute-step (fn [s]
                                       (log/info "Executing:" s)
                                       (.println pw s)
                                       (wait-for-prompt r))]
                    ;; When script has been executed, stop the container
                    (log/debug "Executing" (count script) "commands in container")
                    (let [logs (->> script
                                    (map execute-step)
                                    (doall))]
                      ;; Stop the container by exiting
                      (log/debug "Exiting")
                      (.println pw "exit")
                      (.close r)
                      (assoc state :logs logs))))
        shutdown (fn [{:keys [socket container] :as state}]
                   (log/debug "Closing socket")
                   (.close socket)
                   (log/debug "Stopping container" (:id container))
                   (stop-container client (:id container))
                   (log/info "Done.")
                   state)
        return (fn [{:keys [logs]}]
                 (let [res (inspect-container client cn)]
                   {:exit (get-in res [:state :exit-code])
                    :logs logs}))]
    (some-> conf
            (pull)
            (create)
            (start)
            (attach)
            (execute)
            (shutdown)
            (return))))
