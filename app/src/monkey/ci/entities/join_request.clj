(ns monkey.ci.entities.join-request
  (:require [medley.core :as mc]
            [monkey.ci.entities.core :as ec]))

(def base-query
  {:select [:jr.status :jr.request-msg :jr.response-msg
            [:jr.cuid :id] [:u.cuid :user-id] [:c.cuid :org-id]]
   :from [[:join-requests :jr]]
   :join [[:users :u] [:= :u.id :jr.user-id]
          [:orgs :c] [:= :c.id :jr.org-id]]})

(defn select-join-request-as-entity [conn cuid]
  (some-> (ec/select
           conn
           (assoc base-query :where [:= :jr.cuid cuid]))
          first
          (update :status keyword)
          (as-> x (mc/filter-vals some? x))))

(defn select-user-join-requests [conn user-cuid]
  (ec/select
   conn
   (assoc base-query
          :where [:= :u.cuid user-cuid])))
