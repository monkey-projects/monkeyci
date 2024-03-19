(ns monkey.ci.gui.repo.views
  (:require [monkey.ci.gui.clipboard :as cl]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.repo.events]
            [monkey.ci.gui.repo.subs]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.table :as table]
            [monkey.ci.gui.time :as t]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn- elapsed [b]
  (let [e (u/build-elapsed b)]
    (when (pos? e)
      (t/format-seconds (int (/ e 1000))))))

(defn- builds []
  (rf/dispatch [:builds/load])
  (fn []
    (let [b (rf/subscribe [:repo/builds])]
      (when @b
        [:<>
         [:div.clearfix
          [:h4.float-start "Builds"]
          [:div.float-end
           [co/reload-btn [:builds/load]]]]
         [:p "Found " (count @b) " builds"]
         [table/paged-table
          {:id ::builds
           :items-sub [:repo/builds]
           :columns [{:label "Id"
                      :value (fn [b] [:a {:href (r/path-for :page/build b)} (:build-id b)])}
                     {:label "Time"
                      :value #(t/reformat (:start-time %))}
                     {:label "Elapsed"
                      :value elapsed}
                     {:label "Trigger"
                      :value :source}
                     {:label "Ref"
                      :value (comp :ref :git)}
                     {:label "Result"
                      :value (fn [b] [co/build-result (:status b)])}
                     {:label "Commit message"
                      :value (fn [b]
                               [:span.text-truncate (:or (get-in b [:git :message])
                                                         (:message b))])}]}]]))))

(defn page [route]
  (rf/dispatch [:repo/init])
  (fn [route]
    (let [{:keys [customer-id repo-id] :as p} (get-in route [:parameters :path])
          r (rf/subscribe [:repo/info repo-id])]
      [l/default
       [:<>
        [:h3
         (:name @r)
         [:span.fs-6.p-1
          [cl/clipboard-copy (u/->sid p :customer-id :repo-id) "Click to save the sid to clipboard"]]]
        [:p "Repository url: " [:a {:href (:url @r)} (:url @r)]]
        [co/alerts [:repo/alerts]]
        [builds]
        [:div
         [:a {:href (r/path-for :page/customer {:customer-id customer-id})} "Back to customer"]]]])))
