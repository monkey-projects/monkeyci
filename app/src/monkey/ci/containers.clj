(ns monkey.ci.containers
  "Generic functionality for running containers"
  (:require [monkey.ci
             [config :as c]
             [protocols :as p]
             [runtime :as rt]]))

(defmulti make-container-runner (comp :type :containers))

;;; Configuration handling

(defmulti normalize-containers-config (comp :type :containers))

(defmethod normalize-containers-config :default [conf]
  conf)

(defmethod c/normalize-key :containers [k conf]
  (c/normalize-typed k conf normalize-containers-config))

(defmethod rt/setup-runtime :containers [conf _]
  (when (get conf :containers)
    (make-container-runner conf)))

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
