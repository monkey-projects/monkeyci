(ns monkey.ci.web.api.plan
  (:require [clojure.tools.logging :as log]
            [clojure.string :as cs]
            [monkey.ci
             [storage :as st]
             [time :as t]]
            [monkey.ci.common.constants :as cc]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(defn- active? [now {:keys [valid-from valid-until]}]
  (and (or (nil? valid-from)
           (<= valid-from now))
       (or (nil? valid-until)
           (<= now valid-until))))

(defn- find-current-plan [st org-id]
  (->> (st/list-org-plans st org-id)
       (filter (partial active? (t/now)))
       (first)))

(defn- end-plan-and-subs [st {:keys [org-id] :as plan} at]
  (log/info "Canceling plan:" (:id plan) "at" at)
  (let [cs (st/find-credit-subscription st [org-id (:subscription-id plan)])]
    (st/save-credit-subscription st (assoc cs :valid-until at)))
  (st/save-org-plan st (assoc plan :valid-until at)))

(defn- end-free-subs [st org-id at]
  (->> (st/list-org-credit-subscriptions st org-id)
       (map (fn [cs]
              (st/save-credit-subscription st (assoc cs :valid-until at))))
       (doall)))

(defn- end-current-plan
  "Terminates the current plan for the org by setting the `valid-until` time to
   the specified `at` timestamp.  The associated subscription is also terminated."
  [st org-id at]
  (if-let [plan (find-current-plan st org-id)]
    (end-plan-and-subs st plan at)
    ;; In case there is no plan, cancel the free subscription(s)
    (end-free-subs st org-id at)))

(defmulti validate-plan :type)

(defmethod validate-plan :default [plan]
  plan)

(defmethod validate-plan :free [plan]
  (assoc plan
         :credits cc/free-credits
         :max-users 1))

(defmethod validate-plan :starter [plan]
  (assoc plan
         :credits cc/starter-credits
         :max-users cc/starter-users))

(defmethod validate-plan :pro [plan]
  (when-not (:max-users plan)
    (throw (ex-info "Pro plan must specify number of users" plan)))
  (assoc plan
         :credits cc/pro-credits))

(defn- plan-valid-period [plan]
  (when (= :free (:type plan))
    "P1Y"))

(defn create-plan [req]
  (let [st (c/req->storage req)
        org-id (c/org-id req)
        plan (-> (c/body req)
                 (assoc :org-id org-id
                        :id (st/new-id))
                 (update :valid-from #(or % (t/now)))
                 (validate-plan))
        at (:valid-from plan)
        sub {:id (st/new-id)
             :org-id org-id
             :valid-from at
             :amount (:credits plan)
             :description (str (cs/capitalize (name (:type plan))) " plan")
             :valid-period (plan-valid-period plan)}]
    (end-current-plan st org-id at)
    (if (st/save-credit-subscription st sub)
      (let [plan (assoc plan :subscription-id (:id sub))]
        (if (st/save-org-plan st plan)
          (-> plan
              (rur/response)
              (rur/status 201))
          (throw (ex-info "Failed to create plan" plan))))
      (throw (ex-info "Failed to create plan subscription" plan)))))

(defn get-current [req]
  (if-let [plan (find-current-plan (c/req->storage req) (c/org-id req))]
    (rur/response plan)
    (rur/status 204)))

(defn org-history [req]
  (->> (st/list-org-plans (c/req->storage req) (c/org-id req))
       (sort-by :valid-from)
       (rur/response)))

(defn cancel-plan
  "Cancels the current org plan at the given time (or now) by setting the `valid-until`
   time of the current plan, if any, and creating a new free plan that starts at the
   same time."
  [req]
  (let [st (c/req->storage req)
        org-id (c/org-id req)
        plan (find-current-plan st org-id)
        at (get-in req [:parameters :body :when] (t/now))]
    ;; End previous plan
    (end-current-plan st org-id at)
    ;; Create new free plan
    (let [free-plan {:org-id org-id
                     :id (st/new-id)
                     :type :free
                     :max-users 1
                     :valid-from at}]
      (when-not (st/save-org-plan st free-plan)
        ;; Throw exception to rollback trx
        (throw (ex-info "Failed to create free plan" {:plan free-plan})))
      (rur/response free-plan))))
