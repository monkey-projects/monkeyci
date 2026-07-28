(ns monkey.ci.oci.process-reaper
  "Provides a component that can be used to kill dead containers."
  (:require [monkey.ci.oci.core :as c]
            [monkey.oci.container-instance.core :as ci]))

(defrecord ProcessReaper [config]
  clojure.lang.IFn
  (invoke [this]
    (let [{:keys [containers] :as rc} (:runner config)]
      (if (#{:oci} (:type rc))
        (c/delete-stale-instances (ci/make-context containers) (:compartment-id containers))
        []))))

(defn make-process-reaper [conf]
  (->ProcessReaper conf))
