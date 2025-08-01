(ns ^:dev/always monkey.ci.gui.layout
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
      (println "User:" @u)
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

(defn footer []
  (let [v (rf/subscribe [:version])]
    (tc/footer (assoc t/config :version @v))))

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

(defn default
  "Layout for default application pages"
  [subpanel]
  (rf/dispatch [:core/init-user])
  [:<>
   [header]
   [:div.bg-soft-primary-light.flex-fill
    ;; Relative position necessary for the bg shape to work
    [:div.container.position-relative.zi-2.my-4
     [error-boundary subpanel]]]
   [co/bg-shape]
   [footer]])

(defn public
  "Layout for public pages, without user info"
  [subpanel]
  [:<>
   [t/generic-header t/config nil]
   [:div.bg-soft-primary-light.flex-fill
    ;; Relative position necessary for the bg shape to work
    [:div.container.position-relative.zi-2.my-4
     [error-boundary subpanel]]]
   [co/bg-shape]
   [footer]])
