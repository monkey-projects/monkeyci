(ns monkey.ci.gui.notifications.views
  "Views for notification management, mainly unsubscribing from emails."
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.template :as t]
            [monkey.ci.gui.notifications.events :as e]
            [monkey.ci.gui.notifications.subs :as s]
            [re-frame.core :as rf]))

(defn status-desc []
  (let [u? (rf/subscribe [::s/unregistering?])]
    (if @u?
      [:p "Removing your email address from our list, one moment please..."]
      [:p "Your email address has been removed from our list.  Sorry to see you go!"])))

(defn unsubscribe-email [route]
  (let [opts (-> (get-in route [:parameters :query])
                 (select-keys [:id :email]))]
    (rf/dispatch-sync [::e/unregister-email opts])
    (fn [_]
      [l/public 
       [:<>
        [:h3 "Unsubscribe Email"]
        [status-desc]
        [:img {:src "/img/oc-sending.svg" :width "300em"}]]])))
