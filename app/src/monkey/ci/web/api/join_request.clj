(ns monkey.ci.web.api.join-request
  "API functions for org join requests"
  (:require [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci.storage :as st]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(defn- user-id->body
  "Adds user id from request path parameters to the body"
  [req]
  (update req :parameters (fn [p]
                            (assoc-in p [:body :user-id] (get-in p [:path :user-id])))))


(defn- mark-pending [req]
  (assoc-in req [:parameters :body :status] :pending))

(def create-join-request
  (comp
   (c/entity-creator
    st/save-join-request
    c/default-id)
   mark-pending
   user-id->body))

(def get-join-request
  (c/entity-getter (c/id-getter :join-request-id)
                   st/find-join-request))

(defn- add-orgs [st jrs]
  (let [custs (->> (st/find-orgs st (map :org-id jrs))
                   (group-by :id)
                   (mc/map-vals first))]
    (->> jrs
         (map (fn [jr]
                (assoc jr :org (get custs (:org-id jr))))))))

(defn search-join-requests
  "Retrieves join requests for the user or org, depending on parameters"
  [req]
  (let [{:keys [user-id org-id]} (get-in req [:parameters :path])
        st (c/req->storage req)]
    (log/debug "User id:" user-id)
    (-> (cond
          user-id (->> (st/list-user-join-requests st user-id)
                       (add-orgs st))
          ;; TODO Implement for org
          :else [])
        (rur/response))))

(def delete-join-request
  (c/entity-deleter (c/id-getter :join-request-id) st/delete-join-request))

(defn list-org-join-requests [req]
  ;; TODO Allow filtering by status
  ;; TODO Add user details
  (rur/response (st/list-org-join-requests
                 (c/req->storage req)
                 (get-in req [:parameters :path :org-id]))))

(defn- find-join-request
  "Retrieves join request by id from storage.  Returns `nil` if not found,
   or if the org id in the request does not match the org id in
   the join request."
  [req]
  (let [st (c/req->storage req)
        {:keys [request-id org-id]} (get-in req [:parameters :path])
        jr (st/find-join-request st request-id)]
    (when (and jr (= org-id (:org-id jr)))
      jr)))

(defn- add-user-org [st jr]
  (when-let [u (st/find-user st (:user-id jr))]
    (->> (update u :orgs (comp distinct conj) (:org-id jr))
         (st/save-user st))))

(defn- respond-to-join-request [new-status action req]
  (let [st (c/req->storage req)]
    (if-let [jr (find-join-request req)]
      (let [upd (assoc jr
                       :status new-status
                       :response-msg (:message (c/body req)))]
        (if (action st upd)
          (do
            (st/save-join-request st upd)
            (rur/response upd))
          (rur/status 400)))
      (rur/status 404))))

(def approve-join-request (partial respond-to-join-request :approved add-user-org))
(def reject-join-request (partial respond-to-join-request :rejected (constantly true)))

