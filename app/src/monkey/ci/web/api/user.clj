(ns monkey.ci.web.api.user
  (:require [monkey.ci.storage :as st]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(c/make-entity-endpoints "user"
                         {:get-id (c/id-getter (juxt :user-type :type-id))
                          :getter st/find-user-by-type
                          :saver st/save-user})

(def delete-user
  (c/entity-deleter (c/id-getter :user-id) st/delete-user))

(defn get-user-orgs
  "Retrieves all users linked to the org in the request path"
  [req]
  (let [user-id (get-in req [:parameters :path :user-id])
        st (c/req->storage req)]
    (rur/response (st/list-user-orgs st user-id))))

(c/make-entity-endpoints
 "user-settings"
 {:get-id (c/id-getter :user-id)
  :getter st/find-user-settings
  :saver st/save-user-settings})
