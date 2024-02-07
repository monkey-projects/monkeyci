(ns monkey.ci.workspace
  (:require [monkey.ci
             [blob :as b]
             [config :as c]]))

(defmethod c/normalize-key :workspace [k conf]
  (c/normalize-typed k conf (partial b/normalize-blob-config k)))
