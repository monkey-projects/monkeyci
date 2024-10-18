(ns migration
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [storage :refer [make-storage]]
            [medley.core :as mc]
            [monkey.ci
             [cuid :as cuid]
             [edn :as edn]
             [protocols :as p]
             [storage :as s]]
            [monkey.ci.entities
             [core :as ec]
             [migrations :as em]]))

(defn- migrate-dir [p sid st]
  (let [files (seq (.listFiles p))
        sid-name (fn [f]
                   (let [n (.getName f)
                         l (.lastIndexOf n ".")]
                     (subs n 0 l)))
        make-sid (fn [f]
                   (concat sid [(sid-name f)]))]
    (doseq [f files]
      (cond
        (.isFile f)
        (do
          (log/info "Migrating:" f)
          (p/write-obj st (make-sid f) (edn/edn-> f)))
        (.isDirectory f)
        (migrate-dir f (concat sid [(.getName f)]) st)))))

(defn migrate-from-file-storage
  "Migrates all files from given directory to destiny storage."
  [dir st]
  (let [f (io/file dir)]
    (log/info "Migrating storage from" (.getCanonicalPath f))
    (migrate-dir f [] st)))

(defn- new-id-mapping
  "Updates state by creating a new id and mapping it to the old"
  [state old-id]
  (assoc-in state [:ids old-id] (if (cuid/cuid? old-id) old-id (s/new-id))))

(defn- new-id [state old-id]
  (get-in state [:ids old-id]))

(defn- ->db [cust-id e]
  (assoc e
         :customer-id cust-id
         :id (s/new-id)))

(defn- migrate-params [{:keys [src dest]} from-id to-id]
  (let [params (->> (s/find-params src from-id)
                    (map (partial ->db to-id)))]
    (s/save-params dest to-id params)))

(defn- migrate-ssh-keys [{:keys [src dest]} from-id to-id]
  (let [add-desc (fn [k]
                   (update k :description #(or % "(No description)")))
        ssh-keys (->> (s/find-ssh-keys src from-id)
                      (map (partial ->db to-id))
                      (map add-desc))]
    (s/save-ssh-keys dest to-id ssh-keys)))

(defn- migrate-builds [{:keys [src dest]} from to-id]
  (letfn [(set-job-id [jobs]
            (mc/map-kv-vals (fn [id job]
                              (assoc job :id id))
                            jobs))
          (migrate-build [repo-id idx build]
            (let [mb (-> (s/find-build src [(:id from) repo-id build])
                         ;; Add job id
                         (update-in [:script :jobs] set-job-id)
                         (assoc :customer-id to-id
                                :idx (inc idx))
                         (update :status (fn [s]
                                           (if (or (nil? s) (= :running s))
                                             :canceled
                                             s))))]
              (log/debug "Migrating build" build "with" (count (-> mb :script :jobs)) "jobs")
              (s/save-build dest mb)))
          (migrate-repo [repo]
            (let [b (s/list-builds src [(:id from) (:id repo)])]
              (log/info "Migrating" (count b) "builds for repo" (:id repo))
              (doall (map-indexed (partial migrate-build (:id repo)) b))))]
    (->> from
         :repos
         vals
         (map migrate-repo)
         doall)))

(defn- migrate-customer [{:keys [src dest] :as state} cust-id]
  (log/debug "Migrating customer:" cust-id)
  (let [state (new-id-mapping state cust-id)
        {src-id :id :as src-cust} (s/find-customer src cust-id)
        dest-cust-id (-> src-cust
                         (assoc :id (new-id state cust-id))
                         (as-> c (s/save-customer dest c))
                         (last))]
    (migrate-params state src-id dest-cust-id)
    (migrate-ssh-keys state src-id dest-cust-id)
    (migrate-builds state src-cust dest-cust-id)
    ;; Return updated state
    (update state :customers (fnil conj []) dest-cust-id)))

(defn- migrate-webhook [{:keys [src dest] :as state} id]
  (let [state (new-id-mapping state id)
        in (s/find-webhook src id)
        mig-cust (new-id state (:customer-id in))]
    (log/debug "Migrating webhook:" in)
    (if (some? mig-cust)
      (s/save-webhook dest (assoc in
                                  :customer-id mig-cust
                                  :id (new-id state id)))
      (log/warn "Unable to migrate webhook" id ", no matching customer found for" (:customer-id in)))
    state))

(defn- migrate-webhooks [{:keys [src] :as state}]
  (let [webhooks (p/list-obj src (s/webhook-sid))]
    (log/info "Migrating" (count webhooks) "webhooks")
    (reduce migrate-webhook state webhooks)))

(defn- migrate-user [{:keys [src dest] :as state} id]
  (log/debug "Migrating user:" id)
  (let [in (s/find-user-by-type src id)
        state (new-id-mapping state (:id in))
        update-customers (fn [cust]
                           (map (partial new-id state) cust))]
    (s/save-user dest (-> in
                          (assoc :id (new-id state (:id in)))
                          (update :customers update-customers)))
    state))

(defn- migrate-user-type [{:keys [src] :as state} type]
  (let [users (p/list-obj src [s/global s/users type])]
    (log/info "Migrating" (count users) "users for type" type)
    (reduce (fn [s uid]
              (migrate-user s [type uid]))
            state
            users)))

(defn- migrate-users [{:keys [src] :as state}]
  (->> (p/list-obj src [s/global s/users])
       (reduce migrate-user-type state)))

(defn migrate-to-storage
  "Migrates entities from the given storage to the destination storage by
   listing the customers and migrating their properties and builds."
  [src dest]
  (let [cust (p/list-obj src (s/global-sid :customers))]
    (log/info "Migrating" (count cust) "customers")
    (-> (reduce (fn [state cust-id]
                  (migrate-customer state cust-id))
                {:src src
                 :dest dest}
                cust)
        ;; Must be done after migrating customers
        (migrate-webhooks)
        (migrate-users))))

(defn- make-mem-db-storage []
  (s/make-storage {:storage {:type :sql
                             :url "jdbc:h2:mem:"
                             :username "SA"
                             :password ""}}))

(defn migrate-to-mem!
  "Runs migration from the configured storage to an in-memory h2 db"
  ([src]
   (let [dest (make-mem-db-storage)]
     (em/run-migrations! (-> dest :conn))
     (migrate-to-storage src dest)))
  ([]
   (migrate-to-mem! (make-storage))))

(defn close-dest [{:keys [dest customers]}]
  (some-> dest :conn :ds .close)
  customers)

(defn reindex-builds
  "Reindexes all builds from all repos.  This to fix an earlier migration issue
   where builds were not sorted by timestamp.  Also, a number of builds have
   a zero index because the functionality was not implemented yet."
  [st]
  (let [builds (ec/select (:conn st)
                          {:select [:id :repo-id :start-time :idx]
                           :from [:builds]})
        by-repo (group-by :repo-id builds)
        update-build-idx (fn [{:keys [id idx]}]
                           (ec/update-entity (:conn st)
                                             :builds
                                             {:idx idx
                                              :id id}))]
    (log/info "Found" (count builds) "builds to reindex")
    (doseq [l (vals by-repo)]
      (->> l
           (sort-by :start-time)
           (map-indexed (fn [i b]
                          (assoc b :idx (inc i))))
           (map update-build-idx)
           (doall)))
    (log/info "Done.")))

(defn create-credit-consumptions
  "For the given customer, creates credit consumptions matching all builds of that customer.
   Automatically creates a customer credit, rounded to the nearest thousand."
  [st cust-id]
  (let [cust (s/find-customer st cust-id)
        avail-creds (s/calc-available-credits st cust-id)
        cred-amount 10000]
    (log/info "Creating credit consumptions for customer" (:name cust))
    (loop [repos (vals (:repos cust))
           creds avail-creds
           cred-id (->> (s/list-available-credits st cust-id)
                        (first)
                        :id)]
      (when-let [r (first repos)]
        (log/info "Repository:" (:name r))
        (let [builds (s/list-builds st [cust-id (:id r)])
              repo-creds (->> builds
                              (map :credits)
                              (remove nil?)
                              (reduce + 0))
              [rem cred-id] (if (neg? (- creds repo-creds))
                              (let [cred {:customer-id cust-id
                                          :id (cuid/random-cuid)
                                          :amount cred-amount
                                          :type :user}]
                                (log/info "Provisioning credits")
                                (s/save-customer-credit st cred)
                                [(+ creds cred-amount) (:id cred)])
                              [creds cred-id])]
          (log/info "Creating credit consumptions for" (count builds) "builds, totaling" repo-creds "credits.")
          (doseq [b (filter (comp (fnil pos? 0) :credits) builds)]
            (s/save-credit-consumption st (-> (select-keys b [:build-id :repo-id :customer-id])
                                              (assoc :credit-id cred-id
                                                     :id (cuid/random-cuid)
                                                     :amount (:credits b)
                                                     :consumed-at (:end-time b)))))
          (recur (rest repos)
                 rem
                 cred-id))))))
