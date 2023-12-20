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

(defn icon-btn [i lbl evt]
  [:button.btn.btn-primary {:on-click #(rf/dispatch evt)} [:span [icon i] " " lbl]])

(defn reload-btn [evt]
  (icon-btn :arrow-clockwise "Reload" evt))
