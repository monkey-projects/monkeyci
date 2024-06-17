(ns monkey.ci.gui.apis.bitbucket
  "Functions for invoking the bitbucket api"
  (:require [monkey.ci.gui.apis.common :as c]))

(def api-version "2.0")

(defn api-url [path]
  (str "https://api.bitbucket.org/" api-version path))

(defn api-request [db {:keys [path] :as opts}]
  (cond-> (c/api-request db opts)
    true (update :token #(or % (:bitbucket/token db)))
    path (assoc :uri (api-url path))))
