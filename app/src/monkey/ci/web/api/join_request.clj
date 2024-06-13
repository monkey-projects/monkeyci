(ns monkey.ci.web.api.join-request
  "API functions for customer join requests"
  (:require [clojure.tools.logging :as log]
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

(defn search-join-requests
  "Retrieves join requests for the user or customer, depending on parameters"
  [req]
  (let [{:keys [user-id customer-id]} (get-in req [:parameters :path])
        st (c/req->storage req)]
    (log/debug "User id:" user-id)
    (-> (cond
          user-id (st/list-user-join-requests st user-id)
          :else [])
        (rur/response))))

(def delete-join-request
  (c/entity-deleter (c/id-getter :join-request-id) st/delete-join-request))

(defn list-customer-join-requests [req]
  ;; TODO Allow filtering by status
  (rur/response (st/list-customer-join-requests
                 (c/req->storage req)
                 (get-in req [:parameters :path :customer-id]))))

(defn- find-join-request
  "Retrieves join request by id from storage.  Returns `nil` if not found,
   or if the customer id in the request does not match the customer id in
   the join request."
  [req]
  (let [st (c/req->storage req)
        {:keys [request-id customer-id]} (get-in req [:parameters :path])
        jr (st/find-join-request st request-id)]
    (when (and jr (= customer-id (:customer-id jr)))
      jr)))

(defn- respond-to-join-request [new-status req]
  (let [st (c/req->storage req)]
    (if-let [jr (find-join-request req)]
      (let [upd (assoc jr
                       :status new-status
                       :response-msg (:message (c/body req)))]
        (st/save-join-request st upd)
        (rur/response upd))
      (rur/status 404))))

(def approve-join-request (partial respond-to-join-request :approved))
(def reject-join-request (partial respond-to-join-request :rejected))

