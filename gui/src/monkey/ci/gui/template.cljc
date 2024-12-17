(ns monkey.ci.gui.template
  (:require [monkey.ci.template.components :as tc]))

(def config
  "Template configuration, can be passed to template functions."
  #?(:cljs {:base-url js/hostBase}))

(defn logo
  ([config]
   [:img.img-fluid.rounded {:src (tc/assets-url config "/img/monkeyci-black.png")
                            :title "Placeholder Logo"}])
  ([]
   (logo config)))

(defn generic-header
  "Creates structure for a generic header, that can be used by the initial webpage as well."
  [config & [user-info]]
  [:header.header.container
   [:div.row.border-bottom
    [:div.col-2
     [:div.mt-2 [:a {:href "/"} (logo config)]]]
    [:div.col-10
     [:div.row
      [:div.col-9
       [:h1.display-4 "MonkeyCI"]
       [:p.lead "Unleashing full power to build your code!"]]
      (when user-info
        [:div.col-3.text-end
         user-info])]]]])
