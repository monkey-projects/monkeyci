(ns monkey.ci.web.api.customer
  "Specific customer api routes"
  (:require [java-time.api :as jt]
            [monkey.ci
             [storage :as st]
             [time :as t]]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(defn- query->since [req]
  (get-in req [:parameters :query :since]))

(defn- query->until [req]
  (get-in req [:parameters :query :until]))

(defn- hours-ago [h]
  (- (t/now) (t/hours->millis h)))

(defn recent-builds [req]
  (let [st (c/req->storage req)
        cid (c/customer-id req)]
    (if (st/find-customer st cid)
      (rur/response (st/list-builds-since st cid (or (query->since req)
                                                     (hours-ago 24))))
      (rur/not-found {:message "Customer not found"}))))

(def default-tz "Z")

(defn- group-by-date
  "Groups all entities by date, using the given zone offset"
  [dates zone entities time-prop]
  (letfn [(get-date [e]
            (when-let [t (time-prop e)]
              (-> (jt/instant t)
                  (jt/offset-date-time zone)
                  (t/day-start)
                  ;; Should we format to ISO date instead?
                  (jt/to-millis-from-epoch))))]
    (-> (group-by get-date entities)
        ;; Drop entities without time
        (dissoc nil)
        ;; Also add dates without builds
        (as-> x (merge (zipmap (map jt/to-millis-from-epoch dates)
                               (repeat []))
                       x)))))

(defn- elapsed-seconds [builds]
  (letfn [(elapsed [{:keys [start-time end-time]}]
            (if (and end-time start-time)
              (int (/ (- end-time start-time) 1000))
              0))
          (day-elapsed [[date builds]]
            {:date date 
             :seconds (reduce + 0 (map elapsed builds))})]
    (->> builds
         (map day-elapsed)
         (sort-by :date))))

(defn- consumed-credits [ccos]
  (letfn [(day-consumed [[date ccos]]
            {:date date
             :credits (->> ccos
                           (map :amount)
                           (remove nil?)
                           (reduce + 0))})]
    (->> ccos
         (map day-consumed)
         (sort-by :date))))

(defn stats
  "Retrieves customer statistics, since given time and grouped by specified zone
   offset (or UTC if none given)"
  [req]
  (try
    (let [zone     (-> (get-in req [:parameters :query :zone-offset] default-tz)
                       (jt/zone-offset))
          st       (c/req->storage req)
          since    (or (query->since req)
                       (hours-ago (* 24 31)))
          until    (or (query->until req)
                       (t/now))
          dates    (->> (t/date-seq (jt/offset-date-time since zone))
                        (take-while (partial jt/after? (jt/offset-date-time until zone))))
          cid      (c/customer-id req)
          builds   (st/list-builds-since st cid since)
          ccos     (st/list-customer-credit-consumptions-since st cid since)
          elapsed  (-> (group-by-date dates zone builds :start-time)
                       (elapsed-seconds))
          consumed (-> (group-by-date dates zone ccos :consumed-at)
                       (consumed-credits))]
      (rur/response {:period      {:start (t/now)
                                   :end   (t/now)}
                     :zone-offset (str zone)
                     :stats       {:elapsed-seconds elapsed
                                   :consumed-credits consumed}}))
    (catch java.time.DateTimeException ex
      ;; Most likely invalid zone offset
      (c/error-response (ex-message ex) 400))))

(defn credits
  "Returns details of customer credits"
  [req]
  (let [s (c/req->storage req)
        cust-id (c/customer-id req)
        avail (st/calc-available-credits s cust-id)]
    (rur/response {:available avail})))
