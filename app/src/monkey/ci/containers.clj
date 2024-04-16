(ns monkey.ci.containers
  "Generic functionality for running containers"
  (:require [medley.core :as mc]
            [monkey.ci
             [config :as c]
             [runtime :as rt]]))

;; TODO Rework to use a container runner fn instead
(defmulti run-container (comp :type :containers))

(defn- update-env [cc]
  (mc/update-existing cc :env (partial map (fn [[k v]]
                                             (str k "=" v)))))

(defn rt->container-config
  "Extracts all keys from the context step that have the `container` namespace,
   and drops that namespace."
  [rt]
  (->> rt
       :job
       (mc/filter-keys (comp (partial = "container") namespace))
       (mc/map-keys (comp keyword name))
       (update-env)))

;;; Configuration handling

(defmulti normalize-containers-config (comp :type :containers))

(defmethod normalize-containers-config :default [conf]
  conf)

(defmethod c/normalize-key :containers [k conf]
  (c/normalize-typed k conf normalize-containers-config))

(defmethod rt/setup-runtime :containers [conf _]
  ;; Just return the config, will be reworked later to be more consistent with other runtimes
  (get conf :containers))

(def image :image)
(def env :container/env)
(def cmd :container/cmd)
(def mounts :container/mounts)
(def entrypoint :container/entrypoint)
