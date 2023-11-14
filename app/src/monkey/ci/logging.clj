(ns monkey.ci.logging
  "Handles log configuration and how to process logs from a build script"
  (:require [clojure.java.io :as io]))

(defprotocol LogCapturer
  (log-output [this])
  (handle-stream [this in]))

(defmulti make-logger :type)

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
  (partial ->FileLogger conf))

(deftype OciBucketLogger [conf]
  LogCapturer
  (log-output [_]
    :stream)

  (handle-stream [_ in]))

(defmethod make-logger :oci [conf]
  (fn [& _]
    (->OciBucketLogger conf)))
