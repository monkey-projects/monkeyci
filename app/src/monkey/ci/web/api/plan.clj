(ns monkey.ci.web.api.plan
  (:require #_[java-time.api :as jt]
            [monkey.ci
             [storage :as st]
             [time :as t]]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

#_(defn- parse-date [d]
  (some-> d
          (jt/local-date)
          (jt/offset-date-time (jt/zone-offset))
          (jt/to-millis-from-epoch)))

(defn create-plan [req]
  (let [plan (-> (c/body req)
                 (assoc :org-id (c/org-id req)
                        :id (st/new-id))
                 (update :valid-from #(or % (t/now))))]
    (if-let [r (st/save-org-plan (c/req->storage req) plan)]
      (-> plan
          (rur/response)
          (rur/status 201))
      (c/error-response "Failed to create plan" 500))))

(defn- active? [now {:keys [valid-from valid-until]}]
  (and (<= valid-from now)
       (or (nil? valid-until)
           (<= now valid-until))))

(defn- find-current-plan [st org-id]
  (->> (st/list-org-plans st org-id)
       (filter (partial active? (t/now)))
       (first)))

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
    (when plan
      (st/save-org-plan st (assoc plan :valid-until at)))
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
