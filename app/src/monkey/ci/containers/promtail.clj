(ns monkey.ci.containers.promtail
  "Functions for configuring promtail containers.  This is not a container driver, but
   a utility namespace that is in turn used when pushing build logs to Loki."
  (:require [camel-snake-kebab.core :as csk]
            [clj-yaml.core :as yaml]
            [clojure.walk :as cw]
            [medley.core :as mc]))

;; Default promtail settings
(def promtail-image "docker.io/grafana/promtail")
(def promtail-version "2.9.2")

(defn promtail-config
  "Generates config structure that can be passed to promtail.  The structure should
   be converted to yaml in order to be usable by promtail."
  [conf]
  {:positions
   {:filename "/tmp/positions.yaml"}
   :clients
   [(-> {:url (:loki-url conf)
         :tenant-id (:customer-id conf)}
        (mc/assoc-some :bearer-token (:token conf)))]
   :scrape-configs
   [{:job-name "build-logs"
     :static-configs
     (map (fn [dir]
            {:labels (-> conf
                         (select-keys [:customer-id :repo-id :build-id :job-id])
                         (assoc :__path__ (str dir)))})
          (:dirs conf))}]})

(defn ->yaml [conf]
  (->> (cw/postwalk
        (fn [obj]
          (if (map-entry? obj)
            [(csk/->snake_case (name (first obj))) (second obj)]
            obj))
        conf)
       (yaml/generate-string)))

(def yaml-config (comp ->yaml promtail-config))

(defn promtail-container
  "Generates container configuration for promtail.  This is used to push logs to Loki,
   which can then be retrieved later by the client."
  [{:keys [image-url image-tag] :as conf}]
  {:image-url (str (or image-url promtail-image) ":" (or image-tag promtail-version))
   :display-name "promtail"})
