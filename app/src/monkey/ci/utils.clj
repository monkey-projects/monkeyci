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
  "Executes `h` when the JVM shuts down.  Returns the thread that will
   execute the hook."
  [h]
  (let [t (Thread. h)]
    (.. (Runtime/getRuntime)
        (addShutdownHook t))
    t))

(defn tmp-file
  "Generates a new temporary path"
  [prefix suffix]
  (-> (io/file (System/getProperty "java.io.tmpdir") (str prefix (random-uuid) suffix))
      (.getAbsolutePath)))
