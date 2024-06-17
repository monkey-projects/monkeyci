(ns monkey.ci.gui.apis.github
  "Functions for invoking the github api"
  (:require 
            [monkey.ci.gui.apis.common :as c]
            [re-frame.core :as rf]))

(def api-version "2022-11-28")

(defn api-url [path]
  (str "https://api.github.com" path))

(defn api-request
  "Builds an xhrio request map to acces"
  [db {:keys [path] :as opts}]
  ;; TODO Handle pagination (see the `link` header)
  (cond-> (c/api-request db (-> opts
                                (update :token #(or % (:github/token db)))
                                (dissoc :path)))
    true (assoc-in [:headers "X-GitHub-Api-Version"] api-version)
    path (assoc :uri (api-url path))))

