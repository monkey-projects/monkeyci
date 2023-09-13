(ns monkey.ci.utils
  (:require [clojure.java.io :as io]))

(defn cwd
  "Returns current directory"
  []
  (System/getProperty "user.dir"))

(defn abs-path
  "If `b` is a relative path, will combine it with `a`, otherwise
   will just return `b`."
  [a b]
  (if a
    (if (.isAbsolute (io/file b))
      b
      (str (io/file a b)))
    b))

(defn add-shutdown-hook!
  "Executes `h` when the JVM shuts down."
  [h]
  (.. (Runtime/getRuntime)
      (addShutdownHook (Thread. h))))
