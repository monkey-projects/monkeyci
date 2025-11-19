(ns monkey.ci.events.mailman.db
  "Event handlers that write stuff to the database"
  (:require [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [jobs :as j]
             [storage :as st]
             [time :as t]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.runners.interceptors :as ri]))

(def get-db emi/get-db)

(def get-credits ::credits)

(defn set-credits [ctx c]
  (assoc ctx ::credits c))

(def get-build ::build)

(defn set-build [ctx b]
  (assoc ctx ::build b))

(def get-job ::job)

(defn set-job [ctx job]
  (assoc ctx ::job job))

(def build->sid b/sid)

(defn- without-jobs
  "Removes the jobs from the build, ensuring they are not updated.  This eliminates
   unnecessary update and query operations and reduces risk of stale data with
   multiple replicas."
  [build]
  (update build :script dissoc :jobs))

;;; Interceptors for side effects

(def org-credits
  "Interceptor that fetches available credits for the org associated with the build.
   Assumes that the db is in the context."
  {:name ::org-credits
   :enter (fn [ctx]
            (set-credits ctx (st/calc-available-credits (get-db ctx)
                                                        (get-in ctx [:event :build :org-id]))))})

(def load-build
  {:name ::load-build
   :enter (fn [ctx]
            (set-build ctx (st/find-build (get-db ctx) (get-in ctx [:event :sid]))))})

(defn- transactional
  "Wraps interceptor fn `f` in a transaction"
  [f]
  (fn [ctx]
    (let [orig-db (get-db ctx)]
      (-> orig-db
          (st/transact
           (fn [db]
             (f (emi/set-db ctx db))))
          ;; Restore original db conn
          (emi/set-db orig-db)))))

(def assign-build-idx
  "Interceptor that assigns a new index to the build"
  {:name ::assign-build-idx
   :enter (transactional
           (fn [ctx]
             (let [db (get-db ctx)
                   idx (st/find-next-build-idx db (get-in ctx [:event :sid]))
                   build-id (str "build-" idx)]
               (-> ctx
                   ;; Maybe it's a bad idea to patch the incoming event?
                   (update-in [:event :build] merge {:idx idx
                                                     :build-id build-id})
                   (update-in [:event :sid] (comp #(conj % build-id) vec))))))})

(def save-build
  "Interceptor that saves the build as found in the context result, but does not
   update the jobs."
  {:name ::save-build
   :leave (transactional
           (fn [ctx]
             (let [db (get-db ctx)
                   res (em/get-result ctx)
                   build (let [b (-> (:build res)
                                     (mc/assoc-some :message (:message res)))]
                           (when (and b #_(spec/valid? :entity/build b)
                                      (st/save-build db (without-jobs b)))
                             b))]
               (cond-> ctx
                 (some? build) (set-build build)))))})

(def with-build
  "Combines `load-build` and `save-build` interceptors.  Note that this does not work
   atomically."
  {:name ::with-build
   :enter (:enter load-build)
   :leave (:leave save-build)})

(def create-jobs
  "Creates jobs in the db, as found in the event"
  {:name ::create-jobs
   :enter (fn [{{:keys [sid jobs]} :event :as ctx}]
            (doseq [j jobs]
              (st/save-job (get-db ctx) sid j))
            ctx)})

(def load-job
  {:name ::load-job
   :enter (fn [{{:keys [sid job-id]} :event :as ctx}]
            (set-job ctx (st/find-job (get-db ctx) (concat sid [job-id]))))})

(def save-job
  "Saves the job found in the result build by id specified in the event."
  {:name ::save-job
   :leave (transactional
           (fn [{{:keys [sid job-id] :as evt} :event :as ctx}]
             (let [db (get-db ctx)
                   job (let [j (-> ctx (em/get-result) (get-in [:build :script :jobs job-id]))]
                         (if (and j (st/save-job db sid j))
                           (do (log/debug "Updated job in db:" j)
                               (st/save-job-event db (-> st/build-sid-keys
                                                         (zipmap sid)
                                                         (assoc :job-id job-id
                                                                :event (:type evt)
                                                                :time (:time evt)
                                                                :details evt)))
                               j)
                           (log/warn "Failed to update job in db:" j)))]
               (cond-> ctx
                 job (set-job job)))))})

(def with-job
  {:name ::with-job
   :enter (:enter load-job)
   :leave (:leave save-job)})

(def save-credit-consumption
  "Assuming the result contains a build with credits, creates a credit consumption for
   the associated org."
  {:name ::save-credit-consumption
   :leave (transactional
           (fn [ctx]
             (let [{:keys [credits org-id] :as build} (or (get-build ctx)
                                                          (:build (em/get-result ctx)))
                   storage (get-db ctx)]
               (when (and (some? credits) (pos? credits))
                 (log/debug "Consumed credits for build" (build->sid build) ":" credits)
                 (let [avail (st/list-available-credits storage org-id)]
                   ;; TODO To avoid problems when there are no available credits at this point, we should
                   ;; consider "reserving" one at the start of the build.  We have to do a check at that
                   ;; point anyway.
                   (if (empty? avail)
                     (log/warn "No available org credits for build" (build->sid build))
                     (st/save-credit-consumption storage
                                                 (-> (select-keys build [:org-id :repo-id :build-id])
                                                     (assoc :amount credits
                                                            :consumed-at (t/now)
                                                            :credit-id (-> avail first :id)))))))
               ctx)))})

(def save-runner-details
  (ri/save-runner-details
   (comp :runner-details :event)))

;;; Event handlers

(defn check-credits
  "Checks if credits are available.  Returns either a build/pending or a build/failed."
  [ctx]
  (let [has-creds? (let [c (get-credits ctx)]
                     (and (some? c) (pos? c)))
        build (get-in ctx [:event :build])]
    (if has-creds?
      (b/build-pending-evt build)
      (-> (b/build-end-evt build)
          (assoc :status :error
                 ;; TODO Failure cause keyword
                 :message "No credits available")))))

(defn queue-build
  "Adds the build to the build queue, where it will be picked up by a runner when
   capacity is available."
  [ctx]
  (-> (:event ctx)
      (assoc :type :build/queued)))

(defn- build-update-evt [build]
  (b/build-evt :build/updated build :build build))

(defn- build-update [patch]
  (fn [ctx]
    (let [build (get-build ctx)]
      (-> build
          (patch ctx)
          (build-update-evt)))))

(def build-initializing
  "Updates build state to `initializing`.  Returns a consolidated `build/updated` event."
  ;; TODO Save runner details if any
  (build-update (fn [b _] (assoc b :status :initializing))))

(def build-start
  "Marks build as running."
  (build-update (fn [b {:keys [event]}]
                  (assoc b
                         :status :running
                         :start-time (:time event)
                         :credit-multiplier (:credit-multiplier event)))))

(def build-end
  (build-update (fn [b {:keys [event]}]
                  (-> b
                      (assoc :status (:status event)
                             :end-time (:time event)
                             :credits (b/calc-credits b))
                      (mc/assoc-some :message (:message event))))))

(def build-canceled
  (build-update (fn [b {:keys [event]}]
                  (assoc b
                         :status :canceled
                         :end-time (:time event)
                         :credits (b/calc-credits b)))))

(def script-init
  (build-update (fn [b ctx]
                  (assoc-in b [:script :script-dir] (get-in ctx [:event :script-dir])))))

(def script-start
  (build-update (fn [b ctx]
                  (assoc-in b [:script :jobs] (->> (get-in ctx [:event :jobs])
                                                   (map #(vector (j/job-id %) %))
                                                   (into {}))))))

(def script-end
  (build-update (fn [b {{:keys [message status]} :event}]
                  (-> b
                      (assoc-in [:script :status] status)
                      (mc/assoc-some :message message)))))

(defn- job-update
  "Applies patch to the job in the context.  Also requires the build, as it returns
   a `build/updated` event."
  [patch]
  (fn [ctx]
    (let [job (-> ctx
                  (get-job)
                  (patch ctx))]
      (-> (get-build ctx)
          (assoc-in [:script :jobs (:id job)] job)
          (build-update-evt)))))

(def job-init
  (job-update (fn [job ctx]
                (assoc job
                       :status :initializing
                       :credit-multiplier (get-in ctx [:event :credit-multiplier])))))

(def job-start
  (job-update (fn [job {:keys [event]}]
                (-> job
                    (assoc :status :running
                           :start-time (:time event))
                    (merge (select-keys event [:credit-multiplier]))))))

(def job-end
  (job-update (fn [job {:keys [event]}]
                (-> job
                    (assoc :end-time (:time event))
                    (merge (select-keys event [:status :result]))))))

(def job-skipped
  (job-update (fn [job _]
                (assoc job :status :skipped))))

;;; Event routing configuration

(defn make-routes [storage bus]
  (let [use-db (emi/use-db storage)
        build-int [use-db
                   with-build]
        job-int [use-db
                 load-build
                 with-job]]
    [[:build/triggered
      ;; Checks if the org has credits available, and creates the build in db
      [{:handler check-credits
        :interceptors [use-db
                       org-credits
                       assign-build-idx
                       save-build]}]]

     [:build/pending
      [{:handler queue-build}]]

     ;; TODO Also post build/update on queued, so users can see it

     [:build/initializing
      [{:handler build-initializing
        :interceptors (conj build-int
                            save-runner-details)}]]

     [:build/start
      [{:handler build-start
        :interceptors build-int}]]

     [:build/end
      [{:handler build-end
        :interceptors [use-db
                       save-credit-consumption
                       with-build]}]]

     [:build/canceled
      [{:handler build-canceled
        :interceptors [use-db
                       save-credit-consumption
                       with-build]}]]

     [:script/initializing
      [{:handler script-init
        :interceptors build-int}]]

     [:script/start
      [{:handler script-start
        :interceptors (conj build-int create-jobs)}]]

     [:script/end
      [{:handler script-end
        :interceptors build-int}]]

     [:job/initializing
      [{:handler job-init
        :interceptors job-int}]]

     [:job/start
      [{:handler job-start
        :interceptors job-int}]]

     [:job/end
      [{:handler job-end
        :interceptors job-int}]]

     [:job/skipped
      [{:handler job-skipped
        :interceptors job-int}]]]))
