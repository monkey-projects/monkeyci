(ns monkey.ci.logging
  "Handles log configuration and how to process logs from a build script"
  (:require [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [config :as c]
             [oci :as oci]]
            [monkey.ci.storage.oci :as st]
            [monkey.ci.utils :as u]
            [monkey.oci.os.core :as os]))

(defprotocol LogCapturer
  "Used to allow processes to store log information.  Depending on the implementation,
   this can be local on disk, or some cloud object storage."
  (log-output [this])
  (handle-stream [this in]))

(defmulti make-logger (comp :type :logging))

(deftype InheritLogger []
  LogCapturer
  (log-output [_]
    :inherit)

  (handle-stream [_ _]
    nil))

(defmethod make-logger :inherit [_]
  (fn [& _]
    (->InheritLogger)))

(defmethod make-logger :default [_]
  (fn [& _]
    (->InheritLogger)))

(deftype FileLogger [conf ctx path]
  LogCapturer
  (log-output [_]
    (let [f (apply io/file (or (:dir conf) (io/file (:work-dir ctx) "logs")) path)]
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
                     (log/debug "Waiting for deferred to complete...")
                     (deref d)))
        remove-hook (fn [& _]
                      (when-not @shutdown?
                        (.removeShutdownHook (Runtime/getRuntime) t)))]
    (when (md/deferred? d)
      (.addShutdownHook (Runtime/getRuntime) t)
      (md/on-realized d remove-hook remove-hook))
    d))

(defn sid->path [{:keys [prefix]} path sid]
  (->> (concat [prefix] sid path)
       (remove nil?)
       (cs/join "/")))

(deftype OciBucketLogger [conf ctx path]
  LogCapturer
  (log-output [_]
    :stream)

  (handle-stream [_ in]
    (let [sid (get-in ctx [:build :sid])
          ;; Since the configured path already includes the build id,
          ;; we only use repo id to build the path
          on (sid->path conf path (u/sid->repo-sid sid))]
      (-> (oci/stream-to-bucket (assoc conf :object-name on)
                                in)
          (ensure-cleanup)))))

(defmethod make-logger :oci [conf]
  (fn [ctx path]
    (-> conf
        (oci/ctx->oci-config :logging)
        (->OciBucketLogger ctx path))))

(defn handle-process-streams
  "Given a process return values (as from `babashka.process/process`) and two
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
  (cond->> (str (cs/join st/delim sid) st/delim)
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
  (let [oci-conf (-> conf
                     (oci/ctx->oci-config :logging)
                     (oci/->oci-config))
        client (os/make-client oci-conf)]
    (->OciBucketLogRetriever client oci-conf)))

;;; Configuration handling

(defmulti normalize-logging-config (comp :type :logging))

(defmethod normalize-logging-config :default [conf]
  conf)

(defmethod normalize-logging-config :file [conf]
  (update-in conf [:logging :dir] #(or (u/abs-path %) (u/combine (c/abs-work-dir conf) "logs"))))

(defmethod normalize-logging-config :oci [conf]
  (oci/normalize-config conf :logging))

(defmethod c/normalize-key :logging [k conf]
  (c/normalize-typed k conf normalize-logging-config))
