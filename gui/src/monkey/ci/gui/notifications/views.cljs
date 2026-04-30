(ns monkey.ci.gui.notifications.views
  "Views for notification management, mainly unsubscribing from emails."
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.template :as t]
            [monkey.ci.gui.notifications.events :as e]
            [monkey.ci.gui.notifications.subs :as s]
            [re-frame.core :as rf]))

(defn confirm-status
  ([busy?]
   (if busy?
     [:<>
      [:p "Confirming your email address, one moment please..."]
      [:img {:src "/img/oc-thinking.svg" :width "300em"}]]
     [:<>
      [:p "Your email address has been confirmed.  Welcome!"]
      [:p "You may now close this screen."]
      [:img {:src "/img/oc-hi-five.svg" :width "300em"}]]))
  ([]
   (confirm-status @(rf/subscribe [::s/confirming?]))))

(defn confirm-email [route]
  (rf/dispatch-sync [::e/confirm-email (get-in route [:parameters :query])])
  (fn [_]
    [l/public
     [:<>
      [:h3 "Confirm Email Address"]
      [a/component [::s/alerts]]
      [confirm-status]]]))

(defn status-desc
  ([u?]
   (if u?
     [:p "Removing your email address from our list, one moment please..."]
     [:p "Your email address has been removed from our list.  Sorry to see you go!"]))
  ([]
   (let [u? (rf/subscribe [::s/unregistering?])]
     (status-desc @u?))))

(defn unsubscribe-email [route]
  (let [opts (-> (get-in route [:parameters :query])
                 (select-keys [:id :email]))]
    (rf/dispatch-sync [::e/unregister-email opts])
    (fn [_]
      [l/public 
       [:<>
        [:h3 "Unsubscribe Email"]
        [a/component [::s/alerts]]
        [status-desc]
        [:img {:src "/img/oc-sending.svg" :width "300em"}]]])))
