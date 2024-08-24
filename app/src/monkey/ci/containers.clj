(ns monkey.ci.containers
  "Generic functionality for running containers"
  (:require [monkey.ci
             [config :as c]
             [runtime :as rt]]))

;; TODO Rework to use a container runner fn instead
(defmulti ^:deprecated run-container (comp :type :containers))

(defmulti credit-multiplier-fn (comp :type :containers))

(defmethod credit-multiplier-fn :default [_]
  (constantly 0))

;;; Configuration handling

(defmulti normalize-containers-config (comp :type :containers))

(defmethod normalize-containers-config :default [conf]
  conf)

(defmethod c/normalize-key :containers [k conf]
  (c/normalize-typed k conf normalize-containers-config))

(defmethod rt/setup-runtime :containers [conf _]
  ;; Just return the config, will be reworked later to be more consistent with other runtimes
  (-> (get conf :containers)
      (assoc :credit-consumer (credit-multiplier-fn conf))))

(def image (some-fn :container/image :image))
(def env :container/env)
(def cmd :container/cmd)
(def args :container/args)
(def mounts :container/mounts)
(def entrypoint :container/entrypoint)
(def platform :container/platform)
