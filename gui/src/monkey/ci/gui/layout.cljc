(ns monkey.ci.gui.layout
  (:require [clojure.string :as cs]
            [monkey.ci.template.components :as tc]
            [monkey.ci.gui.breadcrumb :as b]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.template :as t]
            #?(:cljs [monkey.ci.gui.logging :as log])
            [monkey.ci.gui.utils :as u]
            #?(:cljs [reagent.core :as rc])
            [re-frame.core :as rf]))

(defn user-info []
  (let [u (rf/subscribe [:login/user])]
    (when @u
      [:div
       [co/user-avatar @u]
       [:p (:name @u) 
        " | "
        [:a {:href "" :on-click (u/link-evt-handler [:login/sign-off])}
         "sign off"]]])))

(defn header []
  (conj (t/generic-header t/config [user-info])
        [:div.row.mt-1
         [:div.col
          [b/path-breadcrumb]]]))

(defn- footer-col [header links]
  (letfn [(footer-link [[lbl url]]
            (let [e? (ext? url)]
              [:li [:a.link-sm.link-light
                    (cond-> {:href url}
                      e? (assoc :target :_blank))
                    lbl
                    (when e?
                      [:small.ms-1 [co/icon :box-arrow-up-right]])]]))
          (ext? [url]
            (and url (cs/starts-with? url "http")))]
    [:div.col-sm.mb-7.mb-sm-0
     [:span.text-cap.text-primary-light header]
     (->> links
          (map footer-link)
          (into [:ul.list-unstyled.list-py-1.mb-0]))]))

(defn- social-link [icon url]
  [:li.list-inline-item
   [:a.btn.btn-icon.btn-sm.btn-soft-light.rounded-circle
    {:href url
     :target :_blank}
    [co/icon icon]]])

(def host-base
  #?(:cljs js/hostBase))

(defn footer []
  (let [v (rf/subscribe [:version])]
    (tc/footer {:version @v
                :base-url (or host-base "monkeyci.com")})))

(defn error-boundary [target]
  #?(:cljs
     (rc/create-class
      {:constructor
       (fn [this props]
         (set! (.-state this) #js {:error nil}))
       :component-did-catch
       (fn [this e info]
         (log/error "An error occurred:" e))
       :get-derived-state-from-error
       (fn [error]
         #js {:error error})
       :render
       (fn [this]
         (rc/as-element
          (if-let [error (.. this -state -error)]
            [:div
             [:h3 "Something went wrong"]
             [:p "An error has occurred in this component.  We're looking in to it."]
             [co/render-alert {:type :danger
                               :message (str error)}]]
            ;; No error, just render target
            target)))})
     :clj [target]))

(def shape
  [:div.shape-container
   [:div.shape.shape-bottom.zi-1
    [:svg {:view-box "0 0 3000 1000"
           :fill"none"
           :xmlns "http://www.w3.org/2000/svg"}
     [:path {:d "M0 1000V583.723L3000 0V1000H0Z"
             :fill "#fff"}]]]])

(defn default [subpanel]
  [:<>
   [header]
   [:div.bg-soft-primary-light.flex-fill
    [:div.container.my-4
     [error-boundary subpanel]]]
   #_shape ;; FIXME Something with z-index
   [footer]])
