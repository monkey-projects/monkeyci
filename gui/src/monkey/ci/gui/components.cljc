(ns monkey.ci.gui.components
  (:require [re-frame.core :as rf]))

(defn logo []
  [:img.img-fluid.rounded {:src "/img/monkeyci-large.png" :title "Placeholder Logo"}])

(defn render-alert [{:keys [type message]}]
  [:div {:class (str "alert alert-" (name type))} message])

(defn alerts [id]
  (let [s (rf/subscribe id)]
    (when (not-empty @s)
      (->> @s
           (map render-alert)
           (into [:<>])))))

(defn icon [n]
  [:i {:class (str "bi bi-" (name n))}])

(defn icon-btn [i lbl evt & [opts]]
  [:button.btn.btn-primary (merge {:on-click #(rf/dispatch evt)} opts) [:span [icon i] " " lbl]])

(defn reload-btn [evt & [opts]]
  (icon-btn :arrow-clockwise "Reload" evt opts))

(defn breadcrumb [parts]
  [:nav {:aria-label "breadcrumb"}
   (->> (loop [p parts
               r []]
          (let [last? (empty? (rest p))
                v (first p)
                n (if last?
                    [:li.breadcrumb-item.active {:aria-current "page"}
                     (:name v)]
                    [:li.breadcrumb-item 
                     [:a {:href (:url v)} (:name v)]])
                r+ (conj r n)]
            (if last?
              r+
              (recur (rest p) r+))))
        (into [:ol.breadcrumb]))])

(defn build-result [r]
  (let [type (condp = r
               "error" :text-bg-danger
               "failure" :text-bg-danger
               "success" :text-bg-success
               :text-bg-secondary)]
    [:span {:class (str "badge " (name type))} (or r "running")]))

