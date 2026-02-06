(ns monkey.ci.gui.template
  (:require [clojure.string :as cs]
            [monkey.ci.template.components :as tc]))

(def config
  "Template configuration, can be passed to template functions."
  #?(:cljs (cond-> {}
             (exists? js/hostBase)
             (assoc :base-url js/hostBase)
             (exists? js/assetsUrl)
             (assoc :assets-url js/assetsUrl))))

(defn docs-url [path]
  (tc/docs-url config (cond->> path
                        (not (cs/starts-with? path "/")) (str "/"))))

(defn docs-link [path lbl]
  [:a {:href (docs-url path) :target :_blank} lbl [:small.ms-1 [:i.bi.bi-box-arrow-up-right]]])

(defn site-url [path]
  (tc/site-url config path))

(defn logo
  ([config]
   [:img.img-fluid.rounded {:src (tc/assets-url config "/img/monkeyci-black.png")
                            :title "Logo"}])
  ([]
   (logo config)))

(defn generic-header
  "Creates structure for a generic header, that can be used by the initial webpage as well."
  [config & [user-info]]
  ;; Headers must have z-index 2 otherwise bg shape covers it up
  [:header.header.container.zi-2
   [:div.d-flex.border-bottom.gap-2
    [:div.mt-2 [:a {:href "/"} (logo config)]]
    [:div.flex-grow-1
     [:h1.display-4 "MonkeyCI"]
     [:p.lead.text-primary "Unleashing full power to build your code!"]]
    [:div.d-flex.flex-column.align-items-end
     (when user-info
       [:div.text-end.mt-1
        user-info])
     [:a.mt-auto.mb-1
      {:href "https://github.com/monkey-projects/monkeyci/issues/new"
       :target :_blank
       :title "Report an issue"}
      [:i.bi.bi-bug]]]]])
