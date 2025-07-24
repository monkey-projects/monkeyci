(ns monkey.ci.gui.repo.views
  (:require [clojure.string :as cs]
            [monkey.ci.gui.clipboard :as cl]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.modals :as m]
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
    [:button.btn.btn-info
     (cond-> {:type :button
              :on-click #(rf/dispatch [:repo/show-trigger-build])}
       @show? (assoc :disabled true))
     [:span.me-2 [co/icon :boxes]] "Trigger Build"]))

(defn- repo-settings-btn []
  (let [c (rf/subscribe [:route/current])]
    [:a.btn.btn-outline-primary
     {:href (r/path-for :page/repo-edit (get-in @c [:parameters :path]))}
     [:span.me-2 [co/icon :gear]] "Settings"]))

(defn refresh-btn [& [opts]]
  [:button.btn.btn-outline-primary.btn-icon.btn-sm
   (merge opts {:on-click (u/link-evt-handler [:builds/reload])
                :title "Refresh"})
   [co/icon :arrow-clockwise]])

(defn build-actions []
  [:<>
   [repo-settings-btn]
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
          [:div.col
           [:button.btn.btn-primary.me-1
            {:type :submit}
            "Trigger"]
           [co/cancel-btn [:repo/hide-trigger-build]]]]]]])))

(defn- trim-ref [ref]
  (let [prefix "refs/heads/"]
    (if (and ref (cs/starts-with? ref prefix))
      (subs ref (count prefix))
      ref)))

(def table-columns
  [{:label "Build"
    :value (fn [b] [:a {:href (r/path-for :page/build (u/cust->org b))} (:idx b)])
    :sorter (table/prop-sorter :idx)}
   {:label "Time"
    :value (fn [b]
             [:span.text-nowrap (t/reformat (:start-time b))])}
   {:label "Elapsed"
    :value elapsed
    :sorter (table/sorter-fn (partial sort-by elapsed))}
   {:label "Trigger"
    :value :source}
   {:label "Ref"
    :value (fn [b]
             [:span.text-nowrap (trim-ref (get-in b [:git :ref]))])
    :sorter (table/sorter-fn (partial sort-by (comp :ref :git)))}
   {:label "Result"
    :value (fn [b] [:div.text-center [co/build-result (:status b)]])
    :sorter (table/prop-sorter :status)}
   {:label "Message"
    :value (fn [b]
             ;; Can't use css truncation in a table without forcing column widths,
             ;; but this in turn could make tables overflow their container.  So we
             ;; just truncate the text.
             [:span
              (u/truncate
               (or (get-in b [:git :message])
                   (:message b))
               30)])}])

(defn- builds [repo]
  (let [loaded? (rf/subscribe [:builds/loaded?])]
    (when-not @loaded?
      (rf/dispatch [:builds/load]))
    [:<>
     [:div.d-flex.gap-1.align-items-start
      [:h4.me-2.text-primary [:span.me-2 co/build-icon] "Builds"]
      [refresh-btn {:class [:me-auto]}]
      [build-actions]]
     [trigger-form repo]
     (if (and (empty? @(rf/subscribe [:repo/builds]))
              @loaded?)
       [:p "This repository has no builds yet."]
       [table/paged-table
        {:id [::builds (:id @repo)]     ; Make table unique to the repo
         :items-sub [:repo/builds]
         :columns (table/add-sorting table-columns 0 :desc)
         :loading {:sub [:builds/init-loading?]}
         :class [:table-hover]
         :on-row-click #(rf/dispatch [:route/goto :page/build %])}])]))

(defn page [route]
  (rf/dispatch [:repo/init])
  (fn [route]
    (let [{:keys [repo-id] :as p} (get-in route [:parameters :path])
          r (rf/subscribe [:repo/info repo-id])]
      [l/default
       [:<>
        [co/page-title
         [:span.me-2 co/repo-icon] 
         "Repository: " (:name @r)
         [:span.fs-6.p-1
          [cl/clipboard-copy (u/->sid p :org-id :repo-id) "Click to save the sid to clipboard"]]]
        [:p "Repository url: " [:a {:href (:url @r) :target :_blank} (:url @r)]]
        [co/alerts [:repo/alerts]]
        [:div.card
         [:div.card-body
          [builds r]]]]])))

(defn labels
  "Component that allows the user to edit, add or remove repo labels."
  [labels]
  (letfn [(label-entry [idx {:keys [name value] :as lbl}]
            [:div.row.mb-2
             {:key (str idx)}
             [:div.col-5
              [:input.form-control
               {:placeholder "Label name"
                :value name
                :on-change (u/form-evt-handler [:repo/label-name-changed lbl])
                :maxlength 100}]]
             [:div.col-6
              [:input.form-control
               {:placeholder "Label value"
                :value value
                :on-change (u/form-evt-handler [:repo/label-value-changed lbl])
                :maxlength 100}]]
             [:div.col-1
              [:button.btn-close.mt-2
               {:type :button
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
    [:button.btn.btn-primary
     (cond-> {:type :submit}
       @s? (assoc :disabled true))
     [:span.me-2 [co/icon :floppy]] "Save"]))

(def delete-modal-id ::delete-repo-confirm)

(defn confirm-delete-modal
  ([repo]
   [m/modal
    delete-modal-id
    [:h4 "Confirmation"]
    [:div
     [:p "Are you sure you want to delete repository " [:b (:name repo)] "? "
      "This will also delete any builds and artifacts associated with it."]
     [:p "This operation cannot be undone."]]
    [:div.d-flex.gap-2
     [:button.btn.btn-danger
      {:title "Confirm delete"
       :data-bs-dismiss "modal"
       :on-click (u/link-evt-handler [:repo/delete])}
      [:span.me-2 co/delete-icon] "Yes, Delete!"]
     [m/modal-dismiss-btn
      [:span [:span.me-2 co/cancel-icon] "Oops, No"]]]])
  ([]
   (let [repo (rf/subscribe [:repo/info])]
     (confirm-delete-modal @repo))))

(defn- delete-btn [repo]
  (let [repo (rf/subscribe [:repo/info (:id repo)])
        d? (rf/subscribe [:repo/deleting?])]
    (when @repo
      [:div
       [:button.btn.btn-danger
        (cond-> {:title "Delete this repository"
                 :data-bs-toggle :modal
                 :data-bs-target (u/->dom-id delete-modal-id)
                 :on-click u/noop-handler}
          @d? (assoc :disabled true))
        [:span.me-2 co/delete-icon] "Delete"]])))

(defn- edit-form [close-btn]
  (let [e (rf/subscribe [:repo/editing])]
    [:form
     {:on-submit (f/submit-handler [:repo/save])}
     [:div.row
      [:div.col
       [:h5 [co/icon-text :gear "General"]]
       [:div.mb-2
        [f/form-input {:id :url
                       :label "Url"
                       :value (:url @e)
                       :extra-opts
                       {:on-change (u/form-evt-handler [:repo/url-changed])
                        :maxlength 300}
                       :help-msg (str "This is used when manually triggering a build from the UI. "
                                      "Use an ssh url for private repos.")}]]
       [:div.mb-2
        [f/form-input {:id :name
                       :label "Repository name"
                       :value (:name @e)
                       :extra-opts
                       {:on-change (u/form-evt-handler [:repo/name-changed])
                        :maxlength 200}}]]
       [:div.mb-2
        [f/form-input {:id :main-branch
                       :label "Main branch"
                       :value (:main-branch @e)
                       :extra-opts
                       {:on-change (u/form-evt-handler [:repo/main-branch-changed])
                        :maxlength 100}
                       :help-msg
                       "Required when you want to determine the 'main branch' in the build script."}]]
       [:div.mb-2
        [f/form-input {:id :github-id
                       :label "Github Id"
                       :value (:github-id @e)
                       :extra-opts
                       {:read-only true
                        :disabled true}
                       :help-msg
                       "The native Github Id, registered when watching this repo."}]]]
      [:div.col
       [:h5 [co/icon-text :tags "Labels"]]
       [:p.text-body-secondary
        "Labels are used to expose parameters and ssh keys to builds, but also to group repositories. "
        "You can assign any labels you like.  Labels are case-sensitive."]
       [labels (:labels @e)]]
      [:div.row
       [:div.d-flex.gap-2
        [save-btn]
        close-btn
        [:span.ms-auto [delete-btn @e]]]]]]))

(defn edit
  "Displays repo editing page"
  []
  (rf/dispatch [:repo/load+edit])
  (fn []
    (let [route (rf/subscribe [:route/current])
          repo (rf/subscribe [:repo/info (get-in @route [:parameters :path :repo-id])])]
      [:<>
       [l/default
        [:<>
         [co/page-title [:span.me-2 co/repo-icon] (:name @repo) ": Settings"]
         [:div.card
          [:div.card-body
           [co/alerts [:repo/edit-alerts]]
           [edit-form
            [co/close-btn [:route/goto :page/repo (-> @route
                                                      (r/path-params)
                                                      (select-keys [:repo-id :org-id]))]]]]]]]
       ;; Ask for confirmation when deleting
       [confirm-delete-modal @repo]])))

(defn new [route]
  (rf/dispatch-sync [:repo/new])
  (l/default
   [:<>
    [co/page-title [:span.me-2 co/repo-icon] "Watch Repository"]
    [:p
     "Configure a new repository to be watched by " [:i "MonkeyCI"] ". After saving, "
     "the repository will show up in the list on your overview page. If a " [:b "webhook"]
     " is configured, any push will result in a new build."]
    [:div.card
     [:div.card-body
      [co/alerts [:repo/edit-alerts]]
      [edit-form [co/cancel-btn
                  [:route/goto :page/org (r/path-params route)]]]]]]))
