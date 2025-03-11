(ns monkey.ci.gui.admin.clean.views
  (:require [monkey.ci.gui.admin.clean.events :as e]
            [monkey.ci.gui.admin.clean.subs :as s]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn clean-results []
  (let [res (rf/subscribe [::s/clean-results])
        cleaned? (rf/subscribe [::s/cleaned?])]
    (when @cleaned?
      (if (empty? @res)
        [:p "Found no dangling processes.  Everything looks ok."]
        [:<>
          [:p "Found " (count @res) " dangling builds:"]
         [t/paged-table
          {:id ::clean-results
           :items-sub [::s/clean-results]
           :columns [{:label "Customer"
                      :value first}
                     {:label "Repo"
                      :value second}
                     {:label "Build"
                      :value #(nth % 3)}]}]]))))

(defn page [_]
  (let [cleaning? (rf/subscribe [::s/cleaning?])]
    [l/default
     [:<>
      [:h3.mb-3 "Dangling Processes"]
      [:p "Find and delete processes or containers that have been running for too long."]
      [:div.card
       [:div.card-body
        [:button.btn.btn-danger.mb-2
         (cond-> {:on-click (u/link-evt-handler [::e/clean])}
           @cleaning? (assoc :disabled true))
         [:span.me-1 [co/icon :radioactive]] "Check and Delete Processes"]
        [clean-results]
        [co/alerts [::s/clean-alerts]]]]]]))
