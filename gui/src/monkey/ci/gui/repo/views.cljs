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

(def elapsed co/build-elapsed)

(defn- trigger-build-btn []
  (let [show? (rf/subscribe [:repo/show-trigger-form?])]
    [:button.btn.btn-secondary
     (cond-> {:type :button
              :on-click #(rf/dispatch [:repo/show-trigger-build])}
       @show? (assoc :disabled true))
     "Trigger Build"]))

(defn- edit-repo-btn []
  (let [c (rf/subscribe [:route/current])]
    [:a.btn.btn-secondary
     {:href (r/path-for :page/repo-edit (get-in @c [:parameters :path]))}
     [:span.me-1 [co/icon :pencil-fill]] "Edit"]))

(defn build-actions []
  [:<>
   [:span.me-1
    [co/reload-btn [:builds/load]]]
   [:span.me-1
    [trigger-build-btn]]
   [edit-repo-btn]])

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
    (if (and ref (cs/starts-with? ref prefix))
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
         :columns [{:label "Build"
                    :value (fn [b] [:a {:href (r/path-for :page/build b)} (:idx b)])}
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

(defn labels
  "Component that allows the user to edit, add or remove repo labels."
  [labels]
  (letfn [(label-entry [idx {:keys [name value] :as lbl}]
            [:div.row.mb-2
             {:key (str idx)}
             [:div.col-5
              [:input.form-control {:placeholder "Label name"
                                    :value name
                                    :on-change (u/form-evt-handler [:repo/label-name-changed lbl])}]]
             [:div.col-6
              [:input.form-control {:placeholder "Label value"
                                    :value value
                                    :on-change (u/form-evt-handler [:repo/label-value-changed lbl])}]]
             [:div.col-1
              [:button.btn-close {:type :button
                                  :aria-label "Remove label"
                                  :on-click #(rf/dispatch [:repo/label-removed lbl])}]]])
          (add-btn []
            [:button.btn.btn-primary
             {:on-click (u/link-evt-handler [:repo/label-add])}
             [:span.me-1 [co/icon :plus-square]] "Add"])]
    (-> (map-indexed label-entry labels)
        (as-> x (into [:div x]))
        (conj [add-btn]))))

(defn- save-btn []
  (let [s? (rf/subscribe [:repo/saving?])]
    [:button.btn.btn-primary.me-2
     (cond-> {:type :submit}
       @s? (assoc :disabled true))
     [:span.me-2 [co/icon :floppy]] "Save"]))

(defn- edit-form [route]
  (let [e (rf/subscribe [:repo/editing])]
    [:form
     {:on-submit (f/submit-handler [:repo/save])}
     [:div.row
      [:div.col
       [:div.mb-2
        [:label.form-label {:for "name"} "Repository name"]
        [:input.form-control
         {:id "name"
          :value (:name @e)
          :on-change (u/form-evt-handler [:repo/name-changed])}]]
       [:div.mb-2
        [:label.form-label {:for "main-branch"} "Main branch"]
        [:input.form-control
         {:id "main-branch"
          :value (:main-branch @e)
          :on-change (u/form-evt-handler [:repo/main-branch-changed])}]
        [:div.form-text "Required when you want to determine the 'main branch' in the build script."]]
       [:div.mb-2
        [:label.form-label {:for "url"} "Url"]
        [:input.form-control
         {:id "url"
          :value (:url @e)
          :on-change (u/form-evt-handler [:repo/url-changed])}]
        [:div.form-text "This is used when manually triggering a build from the UI."]]
       [:div.mb-2
        [:label.form-label {:for "github-id"} "Github Id"]
        [:input.form-control
         {:id "github-id"
          :value (:github-id @e)
          :read-only true
          :disabled true}]
        [:div.form-text "The native Github Id, registered when watching this repo."]]]
      [:div.col
       [:p "Labels:"]
       [:p.text-body-secondary
        "Labels are used to expose parameters and ssh keys to builds, but also to group repositories. "
        "You can assign any labels you like.  Labels are case-sensitive."]
       [labels (:labels @e)]]
      [:div.row
       [:div.col
        [save-btn]
        [co/cancel-btn [:route/goto :page/repo (-> route
                                                   (r/path-params)
                                                   (select-keys [:repo-id :customer-id]))]]]]]]))

(defn edit
  "Displays repo editing page"
  []
  (rf/dispatch [:repo/load+edit])
  (fn []
    (let [route (rf/subscribe [:route/current])
          repo (rf/subscribe [:repo/info (get-in @route [:parameters :path :repo-id])])]
      [l/default
       [:<>
        [:h3 "Edit Repository: " (:name @repo)]
        [co/alerts [:repo/edit-alerts]]
        [edit-form @route]]])))
