(ns monkey.ci.containers.promtail
  "Functions for configuring promtail containers.  This is not a container driver, but
   a utility namespace that is in turn used when pushing build logs to Loki."
  (:require [camel-snake-kebab.core :as csk]
            [clj-yaml.core :as yaml]
            [clojure.walk :as cw]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [jobs :as j]
             [runtime :as rt]
             [spec :as spec]]
            [monkey.ci.spec.containers :as cspec]))

;; Default promtail settings
(def promtail-image "docker.io/grafana/promtail")
(def promtail-version "3.5")

(defn promtail-config
  "Generates config structure that can be passed to promtail.  The structure should
   be converted to yaml in order to be usable by promtail."
  [conf]
  (spec/valid? ::cspec/promtail-config conf)
  {:server
   {:disable true}
   :positions
   {:filename "/tmp/positions.yaml"}
   :clients
   [(-> {:url (:loki-url conf)
         :tenant-id (:org-id conf)}
        (mc/assoc-some :bearer-token (:token conf)
                       :headers (:headers conf)))]
   :scrape-configs
   [{:job-name "build-logs"
     :static-configs
     (map (fn [path]
            {:labels (-> conf
                         (select-keys [:org-id :repo-id :build-id :job-id])
                         ;; Add it as a string otherwise case conversion will drop the underscores
                         (assoc "__path__" (str path)))})
          (:paths conf))}]})

(defn ->yaml [conf]
  (letfn [(convert [x]
            (if (keyword? x)
              (csk/->snake_case (name x))
              x))]
    (->> (cw/postwalk
          (fn [obj]
            (if (map-entry? obj)
              [(convert (first obj)) (second obj)]
              obj))
          conf)
         (yaml/generate-string))))

(def yaml-config (comp ->yaml promtail-config))

(defn promtail-container
  "Generates container configuration for promtail.  This is used to push logs to Loki,
   which can then be retrieved later by the client."
  [{:keys [image-url image-tag] :as conf}]
  {:image-url (str (or image-url promtail-image) ":" (or image-tag promtail-version))
   :display-name "promtail"})

(defn make-config [pt-config job sid]
  (-> pt-config
      (merge (zipmap b/sid-props sid))
      (assoc :job-id (j/job-id job))))
