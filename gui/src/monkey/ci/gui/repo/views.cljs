(ns monkey.ci.gui.repo.views
  (:require [clojure.string :as cs]
            [monkey.ci.gui.clipboard :as cl]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.forms :as f]
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

(defn- trigger-build-btn []
  (let [show? (rf/subscribe [:repo/show-trigger-form?])]
    [:button.btn.btn-secondary
     (cond-> {:type :button
              :on-click #(rf/dispatch [:repo/show-trigger-build])}
       @show? (assoc :disabled true))
     "Trigger Build"]))

(defn build-actions []
  [:<>
   [:span.me-1
    [co/reload-btn [:builds/load]]]
   [trigger-build-btn]])

(defn- trigger-type []
  [:select.form-select {:name :trigger-type}
   [:option {:value :branch} "Branch"]
   [:option {:value :tag} "Tag"]
   [:option {:value :commit-id} "Commit Id"]])

(defn trigger-form [repo]
  (let [show? (rf/subscribe [:repo/show-trigger-form?])]
    (when @show?
      [:div.card.my-2
       [:div.card-body
        [:h5.card-title "Trigger Build"]
        [:form {:on-submit (f/submit-handler [:repo/trigger-build])}
         [:div.row.mb-3
          [:div.col-2
           [trigger-type]]
          [:div.col-10
           [:input.form-control {:type :text
                                 :name :trigger-ref
                                 :default-value (:main-branch @repo)}]]]
         [:div.row
          [:div.col-2
           [:button.btn.btn-primary.me-1
            {:type :submit}
            "Trigger"]
           [:button.btn.btn-outline-danger
            {:on-click (u/link-evt-handler [:repo/hide-trigger-build])}
            "Cancel"]]]]]])))

(defn- trim-ref [ref]
  (let [prefix "refs/heads/"]
    (if (cs/starts-with? ref prefix)
      (subs ref (count prefix))
      ref)))

(defn- builds [repo]
  (let [b (rf/subscribe [:repo/builds])]
    (if-not @b
      (rf/dispatch [:builds/load])
      [:<>
       [:div.clearfix
        [:h4.float-start "Builds"]
        (when @b
          [:span.badge.text-bg-secondary.ms-2 (count @b)])
        [:div.float-end
         [build-actions]]]
       [trigger-form repo]
       [table/paged-table
        {:id ::builds
         :items-sub [:repo/builds]
         :columns [{:label "Id"
                    :value (fn [b] [:a {:href (r/path-for :page/build b)} (:build-id b)])}
                   {:label "Time"
                    :value (fn [b]
                             [:span.text-nowrap (t/reformat (:start-time b))])}
                   {:label "Elapsed"
                    :value elapsed}
                   {:label "Trigger"
                    :value :source}
                   {:label "Ref"
                    :value (fn [b]
                             [:span.text-nowrap (trim-ref (get-in b [:git :ref]))])}
                   {:label "Result"
                    :value (fn [b] [:div.text-center [co/build-result (:status b)]])}
                   {:label "Message"
                    :value (fn [b]
                             [:span (or (get-in b [:git :message])
                                        (:message b))])}]}]])))

(defn page [route]
  (rf/dispatch [:repo/init])
  (fn [route]
    (let [{:keys [customer-id repo-id] :as p} (get-in route [:parameters :path])
          r (rf/subscribe [:repo/info repo-id])]
      [l/default
       [:<>
        [:h3
         "Repository: " (:name @r)
         [:span.fs-6.p-1
          [cl/clipboard-copy (u/->sid p :customer-id :repo-id) "Click to save the sid to clipboard"]]]
        [:p "Repository url: " [:a {:href (:url @r)} (:url @r)]]
        [co/alerts [:repo/alerts]]
        [builds r]
        [:div
         [:a {:href (r/path-for :page/customer {:customer-id customer-id})} "Back to customer"]]]])))
