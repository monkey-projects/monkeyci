(ns monkey.ci.logging
  "Handles log configuration and how to process logs from a build script"
  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci.oci :as oci]))

(defprotocol LogCapturer
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

(deftype OciBucketLogger [conf ctx path]
  LogCapturer
  (log-output [_]
    :stream)

  (handle-stream [_ in]
    (let [sid (get-in ctx [:build :sid])
          prefix (:prefix conf)
          on (cs/join "/" (->> (concat [prefix] (take 3 sid) path)
                               (remove nil?)))]
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
