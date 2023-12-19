(ns monkey.ci.gui.components
  (:require [re-frame.core :as rf]))

(defn render-alert [{:keys [type message]}]
  [:div {:class (str "alert alert-" (name type))} message])

(defn alerts [id]
  (let [s (rf/subscribe id)]
    (when (not-empty @s)
      (->> @s
           (map render-alert)
           (into [:<>])))))
