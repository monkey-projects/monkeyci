(ns monkey.ci.logging
  "Handles log configuration and how to process logs from a build script"
  (:require [clojure.java.io :as io]))

(defmulti make-logger :type)

(defmethod make-logger :inherit [_]
  (constantly :inherit))

(defmethod make-logger :default [_]
  (constantly :inherit))

(defmethod make-logger :file [{:keys [dir]}]
  (fn file-logger [{:keys [work-dir]} parts]
    (let [f (apply io/file (or dir (io/file work-dir "logs")) parts)]
      (.mkdirs (.getParentFile f))
      f)))
