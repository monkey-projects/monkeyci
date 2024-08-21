(ns monkey.ci.logging
  "Handles log configuration and how to process logs from a build script"
  (:require [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [build :as b]
             [config :as c]
             [oci :as oci]
             [runtime :as rt]
             [sid :as sid]
             [utils :as u]]
            [monkey.oci.os.core :as os]))

(defprotocol LogCapturer
  "Used to allow processes to store log information.  Depending on the implementation,
   this can be local on disk, or some cloud object storage."
  (log-output [this] "Returns something that can be passed to child processes for logging")
  (handle-stream [this in] "Does some async processing on a stream, if necessary"))

(defmulti make-logger (comp :type :logging))

;; Note that inherit logger redirects output to the stdout of the parent,
;; which is not necessarily the same as the log output.  For example, logging
;; to Loki does not capture stdout, only the log appender sends to Loki.
;; This makes the inherit logger not very useful.
(deftype InheritLogger []
  LogCapturer
  (log-output [_]
    :inherit)

  (handle-stream [_ _]
    nil))

(defmethod make-logger :inherit [_]
  (fn [& _]
    (->InheritLogger)))

(deftype StringLogger []
  LogCapturer
  (log-output [_]
    :string)

  (handle-stream [_ _]
    nil))

(defmethod make-logger :string [_]
  (fn [& _]
    (->StringLogger)))

(defmethod make-logger :default [_]
  (fn [& _]
    (->InheritLogger)))

(deftype FileLogger [conf build path]
  LogCapturer
  (log-output [_]
    ;; FIXME Refactor to separate build from rt
    (let [f (apply io/file
                   (:dir conf)
                   (concat (drop-last (b/sid build)) path))]
      (.mkdirs (.getParentFile f))
      f))

  (handle-stream [_ _]
    nil))

(defmethod make-logger :file [conf]
  (partial ->FileLogger (:logging conf)))

(defn- ensure-cleanup
  "Registers a shutdown hook to ensure the deferred is being completed, even if the
   system shuts down.  The shutdown hook is removed on completion.  If we don't do
   this, the multipart streams don't get committed when the vm shuts down in the
   process."
  [d]
  (let [shutdown? (atom false)
        t (Thread. (fn []
                     (reset! shutdown? true)
                     (log/debug "Waiting for upload to complete...")
                     (deref d)
                     (log/debug "Upload completed")))
        remove-hook (fn [& _]
                      (when-not @shutdown?
                        (try 
                          (.removeShutdownHook (Runtime/getRuntime) t)
                          (catch Exception _
                            (log/warn "Unable to remove shutdown hook, process is probably already shutting down.")))))]
    (when (md/deferred? d)
      (.addShutdownHook (Runtime/getRuntime) t)
      (md/on-realized d remove-hook remove-hook))
    d))

(defn sid->path [{:keys [prefix]} path sid]
  (->> (concat [prefix] sid path)
       (remove nil?)
       (cs/join "/")))

(deftype OciBucketLogger [conf build path]
  LogCapturer
  (log-output [_]
    :stream)

  (handle-stream [_ in]
    (let [sid (b/sid build)
          ;; Since the configured path already includes the build id,
          ;; we only use repo id to build the path
          on (sid->path conf path (sid/sid->repo-sid sid))]
      (-> (oci/stream-to-bucket (assoc conf :object-name on) in)
          (ensure-cleanup)))))

(defmethod make-logger :oci [conf]
  (fn [build path]
    (-> conf
        :logging
        (->OciBucketLogger build path))))

(defn handle-process-streams
  "Given a process return value (as from `babashka.process/process`) and two
   loggers, will invoke the `handle-stream` on each logger for out and error
   output.  Returns the process."
  [{:keys [out err] :as proc} loggers]
  (->> [out err]
       (map handle-stream loggers)
       (doall))
  proc)

(defprotocol LogRetriever
  "Interface for retrieving log files.  This is more or less the opposite of the `LogCapturer`.
   It allows to list logs and fetch a log according to path."
  (list-logs [this build-sid]
    "Lists available logs for the build id, with name and size")
  (fetch-log [this build-sid path]
    "Retrieves log for given build id and path.  Returns a stream and its size."))

(deftype FileLogRetriever [dir]
  LogRetriever
  (list-logs [_ build-sid]
    (let [build-dir (apply io/file dir build-sid)
          ->out (fn [p]
                  {:name (str (fs/relativize build-dir p))
                   :size (fs/size p)})]
      ;; Recursively list files in the build dir
      (->> (loop [dirs [build-dir]
                  r []]
             (if (empty? dirs)
               r
               (let [f (fs/list-dir (first dirs))
                     {ffiles false fdirs true} (group-by fs/directory? f)]
                 (recur (concat (rest dirs) fdirs)
                        (concat r ffiles)))))
           (map ->out))))

  (fetch-log [_ build-sid path]
    (let [f (apply io/file dir (concat build-sid [path]))]
      (when (.exists f)
        (io/input-stream f)))))

(defmulti make-log-retriever (comp :type :logging))

(defmethod make-log-retriever :file [conf]
  (->FileLogRetriever (get-in conf [:logging :dir])))

(deftype NoopLogRetriever []
  LogRetriever
  (list-logs [_ _]
    [])
  (fetch-log [_ _ _]
    nil))

(defmethod make-log-retriever :default [_]
  (->NoopLogRetriever))

(defn- sid->prefix [sid {:keys [prefix]}]
  (cond->> (str (cs/join sid/delim sid) sid/delim)
    (some? prefix) (str prefix "/")))

(deftype OciBucketLogRetriever [client conf]
  LogRetriever
  (list-logs [_ sid]
    (let [prefix (sid->prefix sid conf)
          ->out (fn [r]
                  ;; Strip the prefix to retain the relative path
                  (update r :name subs (count prefix)))]
      @(md/chain
        (os/list-objects client (-> conf
                                    (select-keys [:ns :compartment-id :bucket-name])
                                    (assoc :prefix prefix
                                           :fields "name,size")))
        (fn [{:keys [objects]}]
          (->> objects
               (map ->out))))))
  
  (fetch-log [_ sid path]
    ;; TODO Also return object size, so we can tell the client
    ;; FIXME Return nil if file does not exist, instead of throwing an error
    @(md/chain
      (os/get-object client (-> conf
                                (select-keys [:ns :compartment-id :bucket-name])
                                (assoc :object-name (str (sid->prefix sid conf) path))))
      bs/to-input-stream)))

(defmethod make-log-retriever :oci [conf]
  (let [oci-conf (:logging conf)
        client (os/make-client oci-conf)]
    (->OciBucketLogRetriever client oci-conf)))

;;; Configuration handling

(defmulti normalize-logging-config (comp :type :logging))

(defmethod normalize-logging-config :default [conf]
  conf)

(defmethod normalize-logging-config :file [conf]
  (update-in conf [:logging :dir] #(or (u/abs-path %) (u/combine (c/abs-work-dir conf) "logs"))))

(defmethod normalize-logging-config :oci [conf]
  (update conf :logging select-keys [:type :credentials :ns :compartment-id :bucket-name :region]))

(defmethod c/normalize-key :logging [k conf]
  (c/normalize-typed k conf normalize-logging-config))

(defmethod rt/setup-runtime :logging [conf _]
  {:maker (make-logger conf)
   :retriever (make-log-retriever conf)})
