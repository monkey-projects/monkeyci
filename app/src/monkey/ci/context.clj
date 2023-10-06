(ns monkey.ci.context
  "Helper functions for working with the context.  The context is created from the configuration
   and possible command-line arguments, and can hold non-serializable things as well, like
   functions.  It is used by the application to execute functionality."
  (:require [clojure.java.io :as io]
            [monkey.ci.utils :as u]))

(defn checkout-dir
  "Calculates the checkout directory for the build, by combining the checkout
   base directory and the build id."
  [ctx]
  (some-> ctx
          :checkout-base-dir
          (u/combine (get-in ctx [:build :build-id]))))

(defn log-dir
  "Gets the directory where to store log files"
  [ctx]
  (or (some-> (:log-dir ctx) (u/abs-path))
      (u/combine (u/tmp-dir) "logs")))

