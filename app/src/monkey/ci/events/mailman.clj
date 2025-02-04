(ns monkey.ci.events.mailman
  "Mailman-style event handling"
  (:require [clojure.spec.alpha :as spec]
            [clojure.tools.logging :as log]
            [monkey.ci
             [storage :as st]
             [time :as t]]
            [monkey.ci.spec.entities :as se]
            [monkey.mailman
             [core :as mc]
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
            (let [build (let [b (get-result ctx)]
                          (when (and (spec/valid? :entity/build b)
                                     (st/save-build (get-db ctx) b))
                            b))]
              (cond-> ctx
                (some? build) (set-build build))))})

(defn build-event
  "Converts the build object in the context into a build event of given type"
  [type]
  {:name ::build-event
   :leave (fn [ctx]
            (when-let [b (get-build ctx)]
              (set-result ctx [{:type type
                                :time (t/now)
                                :sid (build->sid b)
                                :build b}])))})

;;; Event handlers

(defn check-credits
  "Checks if credits are available.  Returns the build from the event if so, otherwise `nil`."
  [ctx]
  (let [creds (get-credits ctx)]
    (when (and (some? creds) (pos? creds))
      (assoc (get-in ctx [:event :build]) :status :pending))))

(defn make-routes [rt]
  [[:build/triggered [{:handler check-credits
                       :interceptors [(mi/sanitize-result)
                                      (use-db (:storage rt))
                                      customer-credits
                                      (build-event :build/pending)
                                      save-build]}]]])

(defn make-router [rt]
  (mc/router (make-routes rt)))
