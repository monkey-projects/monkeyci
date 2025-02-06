(ns monkey.ci.events.mailman
  "Mailman-style event handling"
  (:require [clojure.spec.alpha :as spec]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [storage :as st]
             [time :as t]]
            [monkey.ci.spec.entities :as se]
            [monkey.mailman
             [core :as mmc]
             [interceptors :as mi]]))

(def get-db ::db)

(defn set-db [ctx db]
  (assoc ctx ::db db))

(def get-credits ::credits)

(defn set-credits [ctx c]
  (assoc ctx ::credits c))

(def get-build ::build)

(defn set-build [ctx b]
  (assoc ctx ::build b))

(def get-result :result)

(defn set-result [ctx r]
  (assoc ctx :result r))

(def build->sid (apply juxt st/build-sid-keys))

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
                          (when (and (spec/valid? :entity/build b)
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

;;; Event routing configuration

(defn make-routes [rt]
  (let [use-db (use-db (:storage rt))]
    [[:build/triggered
      [{:handler check-credits
        :interceptors [use-db
                       customer-credits
                       save-build]}]]

     [:build/pending
      [{:handler queue-build}]]

     [:build/initializing
      [{:handler build-initializing
        :interceptors [use-db
                       with-build]}]]

     [:build/start
      [{:handler build-start
        :interceptors [use-db
                       with-build]}]]

     [:build/end
      [{:handler build-end
        :interceptors [use-db
                       save-credit-consumption
                       with-build]}]]

     [:build/canceled
      [{:handler build-canceled
        :interceptors [use-db
                       save-credit-consumption
                       with-build]}]]]))

(defn make-router [rt]
  (mmc/router (make-routes rt)
             {:interceptors [trace-evt
                             add-time
                             (mi/sanitize-result)]}))
