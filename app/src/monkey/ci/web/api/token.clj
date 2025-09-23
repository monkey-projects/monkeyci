(ns monkey.ci.web.api.token
  "API handlers for user and org tokens"
  (:require [buddy.core
             [codecs :as bcc]
             [hash :as bch]]
            [monkey.ci.storage :as st]
            [monkey.ci.web
             [auth :as auth]
             [common :as c]]
            [ring.util.response :as rur]))

(def req->user-id (comp :user-id :path :parameters))

(defn- generate-token [req]
  (assoc-in req [:parameters :body :token] (auth/generate-api-token)))

(c/make-entity-endpoints
 "user-token"
 {:get-id (c/id-getter (juxt :user-id :token-id))
  :getter st/find-user-token
  :deleter st/delete-user-token})

(defn- patch-user-token [req]
  (assoc-in req [:parameters :body :user-id] (req->user-id req)))

(defn- token-saver [target]
  (fn [st token]
    (target st
            ;; Store the token value as hashed string
            (update token :token auth/hash-pw))))

(def create-user-token (comp (c/entity-creator (token-saver st/save-user-token) c/default-id)
                             generate-token
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

(def create-org-token (comp (c/entity-creator (token-saver st/save-org-token) c/default-id)
                            generate-token
                            patch-org-token))

(defn list-org-tokens [req]
  (-> (st/list-org-tokens (c/req->storage req)
                          (c/org-id req))
      (rur/response)))

