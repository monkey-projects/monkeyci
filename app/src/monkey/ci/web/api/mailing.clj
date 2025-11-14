(ns monkey.ci.web.api.mailing
  "Mailing api handlers"
  (:require [monkey.ci.storage :as st]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(c/make-entity-endpoints
 "mailing"
 {:get-id (comp :mailing-id :path :parameters)
  :getter st/find-mailing
  :saver st/save-mailing
  :deleter st/delete-mailing})

(defn list-mailings [req]
  (let [m (st/list-mailings (c/req->storage req))]
    (-> (rur/response m)
        (rur/status (if (empty? m) 204 200)))))
