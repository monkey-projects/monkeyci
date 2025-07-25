(ns monkey.ci.web.api.repo
  "Repository api handlers"
  (:require [monkey.ci.storage :as st]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(def repo-id c/gen-repo-display-id)

(c/make-entity-endpoints "repo"
                         ;; The repo is part of the org, so combine the ids
                         {:get-id (c/id-getter (juxt :org-id :repo-id))
                          :getter st/find-repo
                          :saver st/save-repo
                          :deleter st/delete-repo
                          :new-id repo-id})

(defn list-webhooks [req]
  (-> (c/req->storage req)
      (st/find-webhooks-for-repo (c/repo-sid req))
      ;; Do not return the secret key, it should remain secret
      (as-> m (map #(dissoc % :secret-key) m))
      (rur/response)))
