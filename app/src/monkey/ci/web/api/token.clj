(ns monkey.ci.web.api.token
  "API handlers for user and org tokens"
  (:require [monkey.ci.storage :as st]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(def req->user-id (comp :user-id :path :parameters))

(c/make-entity-endpoints
 "user-token"
 {:get-id (c/id-getter (juxt :user-id :token-id))
  :getter st/find-user-token
  :deleter st/delete-user-token})

(defn- patch-user-token [req]
  (assoc-in req [:parameters :body :user-id] (req->user-id req)))

(def create-user-token (comp (c/entity-creator st/save-user-token c/default-id)
                             patch-user-token))

(defn list-user-tokens [req]
  (-> (st/list-user-tokens (c/req->storage req)
                           (req->user-id req))
      (rur/response)))

(c/make-entity-endpoints
 "org-token"
 {:get-id (c/id-getter (juxt :org-id :token-id))
  :getter st/find-org-token
  :deleter st/delete-org-token})

(defn- patch-org-token [req]
  (assoc-in req [:parameters :body :org-id] (c/org-id req)))

(def create-org-token (comp (c/entity-creator st/save-org-token c/default-id)
                            patch-org-token))

(defn list-org-tokens [req]
  (-> (st/list-org-tokens (c/req->storage req)
                          (c/org-id req))
      (rur/response)))

