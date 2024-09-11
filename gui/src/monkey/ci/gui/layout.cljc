(ns monkey.ci.gui.layout
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.logging :as log]
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
  [:header.header
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
     [co/path-breadcrumb]]]])

(defn footer []
  (let [v (rf/subscribe [:version])]
    [:footer.footer.mt-auto.mb-2.container
     [:div.border-top.mt-2
      [:span "built by " [:a {:href "https://www.monkey-projects.be"} "Monkey Projects"]]
      [:span.float-end.small "version " @v]]]))

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

(defn welcome
  "Renders welcome panel with the subpanel as a child"
  [subpanel]
  [:<>
   [header]
   [:div.row
    [:div.col
     [co/logo]]
    [:div.col
     subpanel]]
   [footer]])

(defn default [subpanel]
  [:<>
   [:div.container
    [header]
    [error-boundary subpanel]]
   [footer]])
