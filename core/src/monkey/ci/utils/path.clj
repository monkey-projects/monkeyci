(ns monkey.ci.utils.path
  "Path and file related utility functions"
  (:require [babashka.fs :as fs]))

(defn abs-path
  "If `b` is a relative path, will combine it with `a`, otherwise
   will just return `b`."
  ([a b]
   (if a
     (if (fs/absolute? b)
       b
       (str (fs/path a b)))
     b))
  ([p]
   (some-> p
           (fs/canonicalize)
           (str))))
