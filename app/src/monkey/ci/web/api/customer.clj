(ns monkey.ci.web.api.customer
  "Specific customer api routes"
  (:require [clojure.tools.logging :as log]
            [java-time.api :as jt]
            [medley.core :as mc]
            [monkey.ci
             [config :as config]
             [cuid :as cuid]
             [storage :as st]
             [time :as t]]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(def query-params (comp :query :parameters))

(defn- repo->out [r]
  (dissoc r :customer-id))

(defn- repos->out
  "Converts the project repos into output format"
  [p]
  (some-> p
          (mc/update-existing :repos (comp (partial map repo->out) vals))))

(c/make-entity-endpoints "customer"
                         {:get-id (c/id-getter :customer-id)
                          :getter (comp repos->out st/find-customer)
                          :saver st/save-customer})

(defn- maybe-link-user [req st cust-id]
  (let [user (:identity req)
        user? (every-pred :type)]
    ;; When a user is creating the customer, link them up
    (if (user? user)
      (st/save-user st (update user :customers conj cust-id))
      (log/warn "No user in request, so creating customer that is not linked to a user."))))

(defn- create-subscription [st cust-id]
  (let [ts (t/now)
        cs {:id (cuid/random-cuid)
            :customer-id cust-id
            :amount config/free-credits
            :valid-from ts}]
    (when (st/save-credit-subscription st cs)
      (st/save-customer-credit st {:id (cuid/random-cuid)
                                   :customer-id cust-id
                                   :subscription-id (:id cs)
                                   :type :subscription
                                   :amount (:amount cs)
                                   :from-time ts}))))

(defn create-customer [req]
  ;; Remove the transaction when it's configured on all endpoints
  (st/with-transaction (c/req->storage req) st
    (let [creator (c/entity-creator (fn [_ cust]
                                      ;; Use trx storage
                                      (st/save-customer st cust))
                                    c/default-id)]
      (when-let [reply (creator req)]
        (let [cust-id (get-in reply [:body :id])]
          (maybe-link-user req st cust-id)
          (create-subscription st cust-id)
          reply)))))

(defn search-customers [req]
  (let [f (query-params req)]
    (if (empty? f)
      (-> (rur/response {:message "Query must be specified"})
          (rur/status 400))
      (rur/response (st/search-customers (c/req->storage req) f)))))

(def query->since (comp :since query-params))

(def query->until (comp :until query-params))

(defn- hours-ago [h]
  (- (t/now) (t/hours->millis h)))

(defn recent-builds
  "Fetches all builds for the customer that were executed in the past 24 hours, or since
   a given query parameter.  Or the last x builds.  If both query parameters are provided,
   it will do a logical `and` (meaning: the builds from the recent period, and the last 
   number of builds)."
  [req]
  (let [st (c/req->storage req)
        cid (c/customer-id req)
        n (:n (query-params req))]
    (if (st/find-customer st cid)
      (let [rb (st/list-builds-since st cid (or (query->since req)
                                                (hours-ago 24)))
            nb (when (number? n) (st/find-latest-n-builds st cid n))]
        (->> (concat rb nb)
             (distinct)
             (rur/response)))
      (rur/not-found {:message "Customer not found"}))))

(defn latest-builds
  "Fetches the latest build for each repo for the customer.  This is used in the customer
   overview screen."
  [req]
  (-> (st/find-latest-builds (c/req->storage req)
                             (c/customer-id req))
      (rur/response)))

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
