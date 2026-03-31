(ns monkey.ci.gui.dashboard.main.subs
  (:require [medley.core :as mc]
            [monkey.ci.gui.dashboard.main.db :as db]
            [monkey.ci.gui.org.subs]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub ::recent-builds db/get-recent-builds)

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

(defn- parse-trigger [src]
  (condp = src
    "github-app" :push
    ;; TODO More trigger types
    :api))

(defn- parse-ref [ref]
  (when-let [[_ t r] (re-matches #"^refs/([^/]+)/(.+)$" ref)]
    [r t]))

(defn- build-progress [{:keys [status]}]
  (case (keyword status)
    :running 0.5
    :success 1
    :failed 1
    :error 1
    0))

(defn- ->active-build [repos b]
  {:repo (get-in repos [(:repo-id b) :name])
   :status (keyword (:status b))
   :build-idx (:idx b)
   :trigger-type (parse-trigger (:source b))
   :git-ref (first (parse-ref (get-in b [:git :ref])))
   :progress (build-progress b)
   :elapsed (u/build-elapsed b)})

(rf/reg-sub
 ::active-builds
 :<- [::recent-builds]
 :<- [:org/info]
 (fn [[rb org] _]
   (let [repos (->> org
                    :repos
                    (group-by :id)
                    (mc/map-vals first))]
     (map (partial ->active-build repos) rb))))

(rf/reg-sub
 ::n-running-builds
 :<- [::recent-builds]
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
