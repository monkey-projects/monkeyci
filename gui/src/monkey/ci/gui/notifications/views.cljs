(ns monkey.ci.gui.notifications.views
  "Views for notification management, mainly unsubscribing from emails."
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.template :as t]))

(defn unsubscribe-email [route]
  (let [id (get-in route [:parameters :query :id])]
    [l/public 
     [:<>
      [:h3 "Unsubscribe Email"]
      [:p "Your email address has been removed from our list.  Sorry to see you go!"]
      [:img {:src "/img/oc-sending.svg" :width "300em"}]]]))
