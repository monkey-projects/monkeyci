(ns migration
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [config :refer [load-edn]]
            [storage :refer [make-storage]]
            [medley.core :as mc]
            [monkey.ci
             [cuid :as cuid]
             [protocols :as p]
             [storage :as s]]
            [monkey.ci.entities.migrations :as em]))

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
          (p/write-obj st (make-sid f) (load-edn f)))
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

(defn migrate-to-storage
  "Migrates entities from the given storage to the destination storage by
   listing the customers and migrating their properties and builds."
  [src dest]
  (let [cust (p/list-obj src (s/global-sid :customers))]
    (log/info "Migrating" (count cust) "customers")
    (reduce (fn [state cust-id]
              (migrate-customer state cust-id))
            {:src src
             :dest dest}
            cust)
    ;; TODO Webhooks
    ))

(defn- make-mem-db-storage []
  (s/make-storage {:storage {:type :sql
                             :url "jdbc:h2:mem:"
                             :username "SA"
                             :password ""}}))

(defn migrate-to-mem!
  "Runs migration from the configured storage to an in-memory h2 db"
  []
  (let [src (make-storage)
        dest (make-mem-db-storage)]
    (em/run-migrations! (-> dest :conn :ds))
    (migrate-to-storage src dest)))

(defn close-dest [{:keys [dest customers]}]
  (some-> dest :conn :ds .close)
  customers)
