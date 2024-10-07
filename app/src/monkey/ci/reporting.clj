(ns monkey.ci.reporting
  "Provides functions for reporting output.  This can be logging, or printing
   to stdout, or formatting as json, etc..."
  (:require [clojure.tools.logging :as log]
            [monkey.ci.runtime :as rt]))

(defn log-reporter
  "Just logs the input object"
  [obj]
  (log/debug "Reporting:" obj))

(defmulti make-reporter :type)

(defmethod make-reporter :log [_]
  log-reporter)

(defmethod make-reporter :default [_]
  log-reporter)

(defmethod rt/setup-runtime :reporter [conf _]
  (make-reporter (:reporter conf)))
