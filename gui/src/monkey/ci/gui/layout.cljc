(ns monkey.ci.gui.layout
  (:require [clojure.string :as cs]
            [monkey.ci.gui.breadcrumb :as b]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [reagent.core :as rc]
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
  [:header.header.container
   [:div.row.border-bottom
    [:div.col-2
     [:div.mt-2 [:a {:href "/"} [co/logo]]]]
    [:div.col-10
     [:div.row
      [:div.col-9
       [:h1.display-4 "MonkeyCI"]
       [:p.lead "Unleashing full power to build your code!"]]
      [:div.col-3.text-end
       [user-info]]]]]
   [:div.row.mt-1
    [:div.col
     [b/path-breadcrumb]]]])

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

(defn footer []
  (let [v (rf/subscribe [:version])]
    [:footer.footer.bg-primary-dark.border-top.border-white-10
     [:div.container
      [:div.row.content-space-1
       [:div.col-lg-3.mb-5.mb-lg-0
        [:div.mb-5
         [:img {:src "/img/monkeyci-white.png" :width "100px"}]
         [:span.h5.text-light "MonkeyCI"]]]
       [footer-col "Resources"
        [["Blog" "https://www.monkey-projects.be/blog"]
         ["Documentation" "https://docs.monkeyci.com"]]]
       [footer-col "Company"
        [["About us" (r/path-for :home/about)]
         ["Contact" (r/path-for :home/contact)]]]
       [footer-col "Legal"
        [["Terms of use" "todo"]
         ["Privacy policy" "todo"]]]]
      [:div.border-top.border-white-10]
      [:div.row.align-items-md-end.py-5
       [:div.col-md.mb-3.mb-md-0
        [:p.text-white.mb-0
         "Built by " [:a.link-light {:href "https://www.monkey-projects.be"} "Monkey Projects"]]]
       [:div.col-md.d-md-flex.justify-content-md-end
        [:p.text-primary-light.mb-0.small.me-2.pt-2 "version " @v]
        [:ul.list-inline.mb-0
         [social-link :github "https://github.com/monkey-projects/monkeyci"]
         [social-link :slack "https://monkeyci.slack.com"]]]]]]))

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
