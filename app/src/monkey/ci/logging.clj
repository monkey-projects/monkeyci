(ns monkey.ci.logging
  "Handles log configuration and how to process logs from a build script"
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [monkey.ci.build :as b]))

(defprotocol LogCapturer
  "Used to allow processes to store log information.  Depending on the implementation,
   this can be local on disk, or some cloud object storage."
  (log-output [this] "Returns something that can be passed to child processes for logging")
  (handle-stream [this in] "Does some async processing on a stream, if necessary"))

(defmulti make-logger (comp :type :logging))

(defn ->config [conf]
  {:logging conf})

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
    (let [f (apply io/file
                   (:dir conf)
                   (concat (remove nil? (drop-last (b/sid build))) path))]
      (.mkdirs (.getParentFile f))
      f))

  (handle-stream [_ _]
    nil))

(defmethod make-logger :file [conf]
  (partial ->FileLogger (:logging conf)))

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
