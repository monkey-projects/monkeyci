(ns monkey.ci.web.api.mailing
  "Mailing api handlers"
  (:require [monkey.ci
             [storage :as st]
             [time :as t]]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(c/make-entity-endpoints
 "mailing"
 {:get-id (comp :mailing-id :path :parameters)
  :getter st/find-mailing
  :saver st/save-mailing
  :deleter st/delete-mailing})

(defn create-mailing [req]
  (let [ec (c/entity-creator st/save-mailing c/default-id)]
    (-> req
        (assoc-in [:parameters :body :creation-time] (t/now))
        (ec))))

(defn list-mailings [req]
  (let [m (st/list-mailings (c/req->storage req))]
    (-> (rur/response m)
        (rur/status (if (empty? m) 204 200)))))
