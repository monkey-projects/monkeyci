(ns monkey.ci.gui.dashboard.subs
  (:require [monkey.ci.gui.dashboard.db :as db]
            [re-frame.core :as rf]))

(rf/reg-sub
 ::assets-url
 (fn [db _]
   (db/get-assets-url db)))

(rf/reg-sub
 ::metrics
 (fn [db [_ id]]
   (db/get-metrics db id)))

(defn- calc-perc [a b]
  (if b
    (/ a b)
    1))

(rf/reg-sub
 ::metrics-total-runs
 :<- [::metrics :total-runs]
 (fn [v _]
   {:value (:curr-value v)
    :status (:status v)
    :diff (calc-perc (:curr-value v) (:last-value v))
    :progress (calc-perc (:curr-value v) (:avg-value v))}))

(rf/reg-sub
 ::jobs
 (fn [db _]
   (:jobs db)))

(rf/reg-sub
 ::active-builds
 (fn [db _]
   (db/get-active-builds db)))

(rf/reg-sub
 ::n-running-builds
 :<- [::active-builds]
 (fn [a _]
   (->> a
        (filter (comp (partial = :running) :status))
        (count))))

(rf/reg-sub
 ::active-repos
 :<- [::active-builds]
 (fn [a _]
   (->> a
        (group-by :repo)
        (map (fn [[r builds]]
               {:id r
                :status (->> builds
                             (sort-by :build-idx)
                             (last)
                             :status)
                :builds (count builds)})))))
