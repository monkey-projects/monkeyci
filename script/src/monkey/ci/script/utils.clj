(ns monkey.ci.script.utils
  (:require [clojure.repl :as cr]))

(defn- infer-type [job]
  ;; Currently, only container jobs are supported in non-clj files
  (assoc job :type :container))

(defn ->seq
  "Converts `x` into a sequential"
  [x]
  (if (sequential? x) x [x]))

(defn normalize [jobs]
  (->> jobs
       (->seq)
       (map infer-type)))

(defn fn-name
  "If x points to a fn, returns its name without namespace"
  [x]
  (->> (str x)
       (cr/demunge)
       (re-find #".*\/(.*)[\-\-|@].*")
       (second)))
