(ns monkey.ci.web.api.user
  (:require [monkey.ci.storage :as st]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(defn- save-user [st u]
  (st/with-transaction st trx
    (when-let [r (st/save-user trx u)]
      (st/save-user-settings trx {:user-id (:id u)
                                  :receive-mailing true})
      r)))

(c/make-entity-endpoints
 "user"
 {:get-id (c/id-getter (juxt :user-type :type-id))
  :getter st/find-user-by-type
  :saver save-user})

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
