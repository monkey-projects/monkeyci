(ns monkey.ci.app.events.mailman.interceptors
  "Mailman interceptors for the app.  Delegates shared functionality to
   `monkey.ci.events.mailman.interceptors` (core) and adds app-specific
   interceptors that require Manifold or Pedestal."
  (:require [clojure.tools.logging :as log]
            [manifold
             [bus :as mb]
             [deferred :as md]
             [time :as mt]]
            [monkey.ci.events.mailman.interceptors :as core-emi]
            [monkey.mailman.core :as mmc]
            [babashka.process :as bp]))

;;; ─── Delegated from core ────────────────────────────────────────────────────

(def get-result core-emi/get-result)
(def set-result core-emi/set-result)
(def no-result core-emi/no-result)
(def add-time core-emi/add-time)
(def trace-evt core-emi/trace-evt)
(def get-state core-emi/get-state)
(def set-state core-emi/set-state)
(def update-state core-emi/update-state)
(def with-state core-emi/with-state)
(def get-mailman core-emi/get-mailman)
(def set-mailman core-emi/set-mailman)
(def add-mailman core-emi/add-mailman)
(def handle-build-error core-emi/handle-build-error)
(def handle-job-error core-emi/handle-job-error)
(def get-process core-emi/get-process)
(def set-process core-emi/set-process)
(def start-process core-emi/start-process)
(def get-job-ctx core-emi/get-job-ctx)
(def set-job-ctx core-emi/set-job-ctx)
(def update-job-ctx core-emi/update-job-ctx)
(def terminate-when core-emi/terminate-when)

;;; ─── App-only: Manifold bus ─────────────────────────────────────────────────

(defn update-bus
  "Publishes the event to the given manifold bus"
  [bus]
  {:name ::update-bus
   :enter (fn [{:keys [event] :as ctx}]
            (mb/publish! bus (:type event) event)
            ctx)})

(defn realize-deferred
  "Interceptor that takes a deferred and realizes it on leave with the result
   specified in the context."
  [d]
  {:name ::realize-deferred
   :leave (fn [ctx]
            (md/success! d (get-result ctx))
            ctx)})

;;; ─── App-only: Process kill scheduling ─────────────────────────────────────

(def get-process-kill ::proc-kill)

(defn set-process-kill [ctx p]
  ;; Should we save it to state instead?  Would make canceling later on easier.
  (assoc ctx ::proc-kill p))

(defn schedule-process-kill
  "When a process is stored in the context, schedules a kill in the period
   returned by the `timeout-fn`, which takes the context as arg.  Sets a deferred
   in the context that can be used to cancel the kill."
  [timeout-fn]
  {:name ::schedule-proc-kill
   :leave (fn [ctx]
            (let [p (get-process ctx)]
              (cond-> ctx
                p (set-process-kill (mt/in (timeout-fn ctx) #(bp/destroy p))))))})

(defn cancel-process-kill
  "When a kill deferred is present in the context, cancels it.  Otherwise noop."
  [ctx]
  (let [k (get-process-kill ctx)]
    (when k
      (md/success! k :canceled))
    (cond-> ctx
      k (dissoc ctx ::proc-kill))))

;;; ─── App-only: Job context extras ──────────────────────────────────────────

(defn add-job-ctx [jc]
  {:name ::add-job-ctx
   :enter #(set-job-ctx % jc)})

(defn add-job-to-ctx
  "Interceptor that adds the job indicated in the event (by job-id) to the job context.
   This is required by jobs and extensions to be present."
  [get-job]
  {:name ::add-job-to-ctx
   :enter (fn [ctx]
            (let [job (get-job ctx)]
              (update-job-ctx ctx assoc :job job)))})

;;; ─── App-only: Event forwarding ─────────────────────────────────────────────

(defn forwarder
  "Interceptor that forwards events to another broker"
  [id dest]
  {:name id
   :enter (fn [ctx]
            (mmc/post-events (:broker dest) [(:event ctx)])
            ctx)})

;;; ─── App-only: DB injection ─────────────────────────────────────────────────

(def get-db ::db)

(defn set-db [ctx db]
  (assoc ctx ::db db))

(defn use-db
  "Adds storage to the context as ::db"
  [db]
  {:name ::use-db
   :enter #(set-db % db)})
