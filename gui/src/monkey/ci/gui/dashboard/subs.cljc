(ns monkey.ci.gui.dashboard.subs
  (:require [medley.core :as mc]
            [monkey.ci.common.builds :as cb]
            [monkey.ci.gui.dashboard.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.org.subs]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-sub
 ::recent-builds
 :<- [:loader/value db/recent-builds]
 :<- [:org/info]
 (fn [[r org] _]
   (let [repos (->> (:repos org)
                    (group-by :id)
                    (mc/map-vals first))]
     (letfn [(add-repo-name [b]
               (assoc b :repo-name (get-in repos [(:repo-id b) :name])))]
       (->> r
            (map add-repo-name)
            (sort-by :start-time)
            (reverse))))))

(rf/reg-sub
 ::active-repos
 :<- [::recent-builds]
 (fn [r _]
   (->> r
        (group-by :repo-id)
        (map (fn [[_ b]]
               {:repo (first b)
                :builds (count b)})))))

(rf/reg-sub
 ::successful-builds
 :<- [::recent-builds]
 (fn [b _]
   (if (empty? b)
     0
     (let [f (filter cb/finished? b)]
       (/ (count (filter cb/success? f))
          (count f))))))

(rf/reg-sub
 ::avg-duration
 :<- [::recent-builds]
 (fn [b _]
   (if (empty? b)
     0
     (let [f (filter cb/finished? b)]
       (/ (->> (map u/build-elapsed f)
               (reduce + 0))
          (count f))))))
