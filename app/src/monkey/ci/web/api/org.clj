(ns monkey.ci.web.api.org
  "Specific org api routes"
  (:require [clojure.tools.logging :as log]
            [java-time.api :as jt]
            [medley.core :as mc]
            [monkey.ci
             [config :as config]
             [cuid :as cuid]
             [storage :as st]
             [time :as t]]
            [monkey.ci.web
             [common :as c]
             [crypto :as crypto]]
            [ring.util.response :as rur]))

(def query-params (comp :query :parameters))

(defn- repo->out [r]
  (dissoc r :org-id))

(defn- repos->out
  "Converts the project repos into output format"
  [p]
  (some-> p
          (mc/update-existing :repos (comp (partial map repo->out) vals))))

(defn- save-org [st org]
  ;; Since the getter converts repos to a list, convert it back here before saving
  (letfn [(repos->map [{:keys [repos] :as org}]
            (cond-> org
              (sequential? repos) (update :repos (partial zipmap (map :id repos)))))]
    (st/save-org st (repos->map org))))

(defn- find-org
  "Looks up an organization, either by cuid or display id"
  [s id]
  (or (st/find-org s id)
      (st/find-org-by-display-id s id)))

(c/make-entity-endpoints "org"
                         {:get-id (c/id-getter :org-id)
                          :getter (comp repos->out find-org)
                          :saver save-org
                          :deleter st/delete-org})

(defn create-org [req]
  (st/with-transaction (c/req->storage req) st
    (let [org-id (cuid/random-cuid)
          org (assoc (c/body req) :id org-id)
          res (st/init-org st {:org org
                               :user-id (-> req :identity :id)
                               :credits {:amount config/free-credits
                                         :from (t/now)}
                               :dek (:enc (crypto/generate-dek req org-id))})]
      (-> (st/find-org st (last res))
          (rur/response)
          (rur/status 201)))))

(defn search-orgs [req]
  (let [f (query-params req)]
    (if (empty? f)
      (-> (rur/response {:message "Query must be specified"})
          (rur/status 400))
      (rur/response (st/search-orgs (c/req->storage req) f)))))

(def query->since (comp :since query-params))

(def query->until (comp :until query-params))

(defn- hours-ago [h]
  (- (t/now) (t/hours->millis h)))

(defn recent-builds
  "Fetches all builds for the org that were executed in the past 24 hours, or since
   a given query parameter.  Or the last x builds.  If both query parameters are provided,
   it will do a logical `and` (meaning: the builds from the recent period, and the last 
   number of builds)."
  [req]
  (let [st (c/req->storage req)
        cid (c/org-id req)
        n (:n (query-params req))]
    (if (st/find-org st cid)
      (let [rb (st/list-builds-since st cid (or (query->since req)
                                                (hours-ago 24)))
            nb (when (number? n) (st/find-latest-n-builds st cid n))]
        (->> (concat rb nb)
             (distinct)
             (rur/response)))
      (rur/not-found {:message "Org not found"}))))

(defn latest-builds
  "Fetches the latest build for each repo for the org.  This is used in the org
   overview screen."
  [req]
  (-> (st/find-latest-builds (c/req->storage req)
                             (c/org-id req))
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
  "Retrieves org statistics, since given time and grouped by specified zone
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
          cid      (c/org-id req)
          builds   (st/list-builds-since st cid since)
          ccos     (st/list-org-credit-consumptions-since st cid since)
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
  "Returns details of org credits"
  [req]
  (let [s (c/req->storage req)
        org-id (c/org-id req)
        avail (st/calc-available-credits s org-id)]
    (rur/response {:available avail})))
