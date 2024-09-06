(ns monkey.ci.containers
  "Generic functionality for running containers"
  (:require [monkey.ci
             [config :as c]
             [protocols :as p]
             [runtime :as rt]]))

;;; Configuration handling

(defmulti normalize-containers-config (comp :type :containers))

(defmethod normalize-containers-config :default [conf]
  conf)

(defmethod c/normalize-key :containers [k conf]
  (c/normalize-typed k conf normalize-containers-config))

(def image (some-fn :container/image :image))
(def env :container/env)
(def cmd :container/cmd)
(def args :container/args)
(def mounts :container/mounts)
(def entrypoint :container/entrypoint)
(def platform :container/platform)

(def props
  "Serializable properties for container jobs"
  [:image :container/image env cmd args entrypoint])
