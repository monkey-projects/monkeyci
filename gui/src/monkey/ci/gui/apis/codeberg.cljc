(ns monkey.ci.gui.apis.codeberg
  "Functions for invoking the Codeberg API"
  (:require [monkey.ci.gui.apis.common :as c]
            [monkey.ci.gui.login.db :as ldb]))

(defn api-url [path]
  (str "https://codeberg.org" path))

(defn api-request [db {:keys [path] :as opts}]
  (cond-> (c/api-request (-> opts
                             (update :token #(or % (ldb/codeberg-token db)))
                             (dissoc :path)))
    path (assoc :uri (api-url path))))
