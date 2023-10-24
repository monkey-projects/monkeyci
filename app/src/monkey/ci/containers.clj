(ns monkey.ci.containers
  "Generic functionality for running containers"
  (:require [medley.core :as mc]))

(defmulti run-container (comp :type :containers))

(defn- update-env [cc]
  (mc/update-existing cc :env (partial map (fn [[k v]]
                                             (str k "=" v)))))

(defn ctx->container-config
  "Extracts all keys from the context step that have the `container` namespace,
   and drops that namespace."
  [ctx]
  (->> ctx
       :step
       (mc/filter-keys (comp (partial = "container") namespace))
       (mc/map-keys (comp keyword name))
       (update-env)))
