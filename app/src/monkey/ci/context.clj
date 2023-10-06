(ns monkey.ci.context
  "Helper functions for working with the context.  The context is created from the configuration
   and possible command-line arguments, and can hold non-serializable things as well, like
   functions.  It is used by the application to execute functionality."
  (:require [clojure.java.io :as io]
            [monkey.ci.utils :as u]))

(defn- combine [a b]
  (.getCanonicalPath (io/file a b)))

(defn work-dir
  "Gets the working directory from the context, as an absolute path"
  [ctx]
  (or (get-in ctx [:build :work-dir])
      (:work-dir ctx)))

(defn log-dir
  "Gets the directory where to store log files"
  [ctx]
  (or (some-> (:log-dir ctx) (u/abs-path))
      (combine (u/tmp-dir) "logs")))

(defn checkout-dir
  "Gets the checkout directory, where to check out git repo's"
  [ctx]
  (combine (work-dir ctx) "checkout"))
