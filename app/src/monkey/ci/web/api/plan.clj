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

(defn get-current [req]
  (if-let [plan (->> (st/list-org-plans (c/req->storage req) (c/org-id req))
                     (filter (partial active? (t/now)))
                     (first))]
    (rur/response plan)
    (rur/status 204)))

(defn org-history [req]
  (->> (st/list-org-plans (c/req->storage req) (c/org-id req))
       (sort-by :valid-from)
       (rur/response)))
