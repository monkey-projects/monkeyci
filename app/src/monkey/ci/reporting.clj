(ns monkey.ci.reporting
  "Provides functions for reporting output.  This can be logging, or printing
   to stdout, or formatting as json, etc..."
  (:require [clansi :as cl]
            [clojure.tools.logging :as log]))

(defn log-reporter
  "Just logs the input object"
  [obj]
  (log/debug obj))

(defn print-reporter
  "Nicely prints to stdout, using coloring"
  [obj]
  (println obj))
