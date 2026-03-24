(ns monkey.ci.plans
  (:require [clojure.string :as cs]
            [java-time.api :as jt]
            [monkey.ci
             [cuid :as cuid]
             [time :as t]]
            [monkey.ci.common.constants :as cc]))

(def free-plan {:type :free})

(defmulti validate-plan :type)

(defmethod validate-plan :default [plan]
  plan)

(defmethod validate-plan :free [plan]
  (assoc plan
         :credits cc/free-credits
         :max-users 1))

(defmethod validate-plan :starter [plan]
  (assoc plan
         :credits cc/starter-credits
         :max-users cc/starter-users))

(defmethod validate-plan :pro [plan]
  (when-not (:max-users plan)
    (throw (ex-info "Pro plan must specify number of users" plan)))
  (assoc plan
         :credits cc/pro-credits))

(defn make-org-plan
  "Creates a plan for the org, using the given plan template.  If no template is
   given, creates a free plan."
  [org-id plan]
  (-> (or plan free-plan)
      (assoc :org-id org-id)
      (update :valid-from #(or % (t/now)))
      (validate-plan)))

(defn- plan-valid-period [plan]
  (when (= :free (:type plan))
    "P1Y"))

(defn plan->sub
  "Creates a new subscription entity for the specified plan."
  [plan]
  {:id (cuid/random-cuid)
   :org-id (:org-id plan)
   :valid-from (:valid-from plan)
   :amount (:credits plan)
   :description (str (cs/capitalize (name (:type plan))) " plan")
   :valid-period (plan-valid-period plan)})

(defn calc-expiration-time [{p :valid-period} ts]
  (when p
    (t/plus-period ts (jt/period p))))

(defn sub->credits
  "Creates a credits entity from given subscription using given timestamp to
   calculate valid times."
  [cs ts]
  (-> (select-keys cs [:org-id :amount])
      (assoc :id (cuid/random-cuid)
             :type :subscription
             :subscription-id (:id cs)
             :valid-from ts
             :valid-until (calc-expiration-time cs ts))))
