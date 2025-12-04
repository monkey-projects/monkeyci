(ns monkey.ci.web.api.user
  (:require [monkey.ci.storage :as st]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(def user-id (comp :user-id :path :parameters))

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
  (let [st (c/req->storage req)]
    (rur/response (st/list-user-orgs st (user-id req)))))

#_(c/make-entity-endpoints
 "user-settings"
 {:get-id (c/id-getter :user-id)
  :getter st/find-user-settings
  :saver st/save-user-settings})

(defmacro require-user [req & body]
  `(if (st/find-user (c/req->storage ~req) (user-id ~req))
     ~@body
     (rur/status 404)))

(defn get-user-settings [req]
  (require-user req
    (rur/response (or (st/find-user-settings (c/req->storage req) (user-id req)) {}))))

(defn update-user-settings [req]
  (let [uid (user-id req)
        s (-> (c/body req)
              (assoc :user-id uid))
        st (c/req->storage req)]
    (require-user req
      (if (st/save-user-settings st s)
        (rur/response s)
        (c/error-response "Unable to save user settings" 500)))))
