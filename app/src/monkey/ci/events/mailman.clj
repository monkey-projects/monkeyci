(ns monkey.ci.events.mailman
  "Mailman-style event handling"
  (:require [clojure.spec.alpha :as spec]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.bus :as mb]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [jobs :as j]
             [storage :as st]
             [time :as t]]
            [monkey.ci.events.mailman
             [bridge :as emb]
             [jms :as emj]]
            [monkey.ci.spec.entities :as se]
            [monkey.mailman
             [core :as mmc]
             [interceptors :as mi]
             [jms :as mj]
             [manifold :as mm]]))

(def get-db ::db)

(defn set-db [ctx db]
  (assoc ctx ::db db))

(def get-credits ::credits)

(defn set-credits [ctx c]
  (assoc ctx ::credits c))

(def get-build ::build)

(defn set-build [ctx b]
  (assoc ctx ::build b))

(def get-job ::job)

(defn set-job [ctx job]
  (assoc ctx ::job job))

(def get-result :result)

(defn set-result [ctx r]
  (assoc ctx :result r))

(def build->sid st/ext-build-sid)

;;; Interceptors for side effects

(defn use-db
  "Adds storage to the context as ::db"
  [db]
  {:name ::use-db
   :enter #(set-db % db)})

(def customer-credits
  "Interceptor that fetches available credits for the customer associated with the build.
   Assumes that the db is in the context."
  {:name ::customer-credits
   :enter (fn [ctx]
            (set-credits ctx (st/calc-available-credits (get-db ctx)
                                                        (get-in ctx [:event :build :customer-id]))))})

(def load-build
  {:name ::load-build
   :enter (fn [ctx]
            (set-build ctx (st/find-build (get-db ctx) (get-in ctx [:event :sid]))))})

(def save-build
  {:name ::save-build
   :leave (fn [ctx]
            (let [build (let [b (:build (get-result ctx))]
                          (when (and b #_(spec/valid? :entity/build b)
                                     (st/save-build (get-db ctx) b))
                            b))]
              (cond-> ctx
                (some? build) (set-build build))))})

(def with-build
  "Combines `load-build` and `save-build` interceptors.  Note that this does not work
   atomically."
  ;; TODO Check if we should lock the build records first
  {:name ::with-build
   :enter (:enter load-build)
   :leave (:leave save-build)})

(def load-job
  {:name ::load-job
   :enter (fn [{{:keys [sid job-id]} :event :as ctx}]
            (set-job ctx (st/find-job (get-db ctx) (concat sid [job-id]))))})

(def save-job
  "Saves the job found in the result build by id specified in the event."
  {:name ::save-job
   :leave (fn [{{:keys [sid job-id]} :event :as ctx}]
            (let [job (let [j (-> ctx (get-result) (get-in [:build :script :jobs job-id]))]
                        (when (and j (st/save-job (get-db ctx) sid j))
                          j))]
              (cond-> ctx
                job (set-job job))))})

(def with-job
  {:name ::with-job
   :enter (:enter load-job)
   :leave (:leave save-job)})

(def save-credit-consumption
  "Assuming the result contains a build with credits, creates a credit consumption for
   the associated customer."
  {:name ::save-credit-consumption
   :leave (fn [ctx]
            (let [{:keys [credits customer-id] :as build} (or (get-build ctx)
                                                              (:build (get-result ctx)))
                  storage (get-db ctx)]
              (when (and (some? credits) (pos? credits))
                (log/debug "Consumed credits for build" (build->sid build) ":" credits)
                (let [avail (st/list-available-credits storage customer-id)]
                  ;; TODO To avoid problems when there are no available credits at this point, we should
                  ;; consider "reserving" one at the start of the build.  We have to do a check at that
                  ;; point anyway.
                  (if (empty? avail)
                    (log/warn "No available customer credits for build" (build->sid build))
                    (st/save-credit-consumption storage
                                                (-> (select-keys build [:customer-id :repo-id :build-id])
                                                    (assoc :amount credits
                                                           :consumed-at (t/now)
                                                           :credit-id (-> avail first :id)))))))
              ctx))})

(def add-time
  {:name ::add-evt-time
   :leave (letfn [(set-time [evt]
                    (update evt :time #(or % (t/now))))]
            (fn [ctx]
              (update ctx :result (partial map set-time))))})

(def trace-evt
  "Logs event info, for debugging purposes."
  {:name ::trace
   :enter (fn [ctx]
            (log/trace "Incoming event:" (:event ctx))
            ctx)
   :leave (fn [ctx]
            (log/trace "Result from handling" (get-in ctx [:event :type]) ":" (:result ctx))
            ctx)})

(defn update-bus [bus]
  "Publishes the event to the given manifold bus"
  {:name ::update-bus
   :enter (fn [{:keys [event] :as ctx}]
            (mb/publish! bus (:type event) event)
            ctx)})

;;; Event handlers

(defn check-credits
  "Checks if credits are available.  Returns either a build/pending or a build/failed."
  [ctx]
  (let [has-creds? (let [c (get-credits ctx)]
                     (and (some? c) (pos? c)))]
    (cond-> {:type :build/pending
             :sid (build->sid (get-in ctx [:event :build]))
             :build (-> (get-in ctx [:event :build])
                        ;; TODO Build lifecycle property
                        (assoc :status :pending))}
      (not has-creds?) (assoc :type :build/failed
                              ;; TODO Failure cause keyword
                              :message "No credits available"))))

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
    (-> (get-build ctx)
        (patch ctx)
        (build-update-evt))))

(def build-initializing
  "Updates build state to `initializing`.  Returns a consolidated `build/updated` event."
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
  (let [use-db (use-db storage)
        build-int [use-db
                   with-build]
        job-int [use-db
                 load-build
                 with-job]]
    [[:build/triggered
      [{:handler check-credits
        :interceptors [use-db
                       customer-credits
                       save-build]}]]

     [:build/pending
      [{:handler queue-build}]]

     [:build/initializing
      [{:handler build-initializing
        :interceptors build-int}]]

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

     [:build/updated
      [{:handler (constantly nil)
        :interceptors [(update-bus bus)]}]]

     [:script/initializing
      [{:handler script-init
        :interceptors build-int}]]

     [:script/start
      [{:handler script-start
        :interceptors build-int}]]

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

(def global-interceptors [trace-evt
                          add-time
                          (mi/sanitize-result)])

(defn make-router [routes]
  (mmc/router routes
              {:interceptors global-interceptors}))

(defn merge-routes
  "Merges multiple routes together into one routing config"
  [routes & others]
  (letfn [(merge-one [a b]
            (merge-with concat (into {} a) (into {} b)))]
    (loop [res []
           todo (cons routes others)]
      (if (empty? todo)
        (seq res)
        (recur (merge-one res (first todo)) (rest todo))))))

;;; Components

(defmulti make-component :type)

(defrecord ManifoldComponent [broker routes]
  co/Lifecycle
  (start [this]
    (let [broker (mm/manifold-broker {})]
      (assoc this
             :broker broker
             :listener (mmc/add-listener broker (make-router (:routes routes))))))

  (stop [{:keys [listener] :as this}]
    (when listener
      (mmc/unregister-listener listener))
    (dissoc this :listener)))

(defmethod make-component :manifold [_]
  (map->ManifoldComponent {}))

(defrecord JmsComponent [config broker routes]
  co/Lifecycle
  (start [this]
    (let [broker (mj/jms-broker (assoc config
                                       :destination-mapper (comp (emj/event-destinations config) :type)))
          router (make-router (:routes routes))
          bridge-dest (get-in config [:bridge :dest])]
      ;; TODO Add listeners for each destination referred to by route event types
      ;; but split up the routes so only those for the destination are added
      (cond-> this
        true (assoc :broker broker
                    :listeners (->> (emj/event-destinations config)
                                    (vals)
                                    (map (partial hash-map :handler router :destination))
                                    (map (partial mmc/add-listener broker))
                                    (doall)))
        ;; Listen to legacy events, if configured
        bridge-dest (assoc :bridge (mmc/add-listener broker {:destination bridge-dest
                                                             :handler (mmc/router emb/bridge-routes)})))))

  (stop [this]
    (when broker
      (mj/disconnect broker))
    (-> this
        (assoc :broker nil)
        (dissoc :bridge :listeners))))

(defmethod make-component :jms [config]
  (map->JmsComponent {:config config}))
