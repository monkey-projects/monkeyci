(ns monkey.ci.workspace
  (:require [monkey.ci
             [blob :as b]
             [config :as c]
             [runtime :as rt]]))

(defmethod c/normalize-key :workspace [k conf]
  (c/normalize-typed k conf (partial b/normalize-blob-config k)))

(defmethod rt/setup-runtime :workspace [conf k]
  (when (k conf)
    (b/make-blob-store conf k)))
