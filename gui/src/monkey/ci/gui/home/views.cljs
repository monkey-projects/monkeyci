(ns monkey.ci.gui.home.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.utils :as u]
            [monkey.ci.gui.home.events]
            [monkey.ci.gui.home.subs]
            [monkey.ci.gui.template :as template]
            [monkey.ci.template.components :as tc]
            [re-frame.core :as rf]))

(defn- create-cust-btn []
  [:a.btn.btn-primary
   {:href (r/path-for :page/org-new)}
   [:span.me-2 [co/icon :plus-square]] "Create New Organization"])

(defn- join-cust-btn []
  [:a.btn.btn-primary
   {:href (r/path-for :page/org-join)}
   [:span.me-2 [co/icon :arrow-right-square]] "Join Organization"])

(defn- cust-item [{:keys [id name owner?]}]
  [:div.card
   {:style {:width "20em"}
    :on-click #(rf/dispatch [:route/goto :page/org {:org-id id}])}
   [:div.card-header {:style {:font-size "5em"}}
    co/org-icon]
   [:div.card-body
    [:div.card-title
     [:h5
      [:a {:href (r/path-for :page/org {:org-id id})}
       name
       (when owner?
         [:span.badge.text-bg-success.ms-2 "owner"])]]]]])

(defn- linked-orgs [cust]
  [:div
   (->> (map cust-item cust)
        (into [:div.d-flex.gap-3.mb-3]))
   [:p "You can join any number of organizations, as long as an organization administrator approves your request."]
   [join-cust-btn]])

;; TODO Configure centrally somewhere
(def free-credits 1000)

(defn- no-orgs []
  [:<>
   [:p "No organizations have been linked to your account yet.  You could either "
    [:a {:href (r/path-for :page/org-new)} "create a new one"]
    " or "
    [:a {:href (r/path-for :page/org-join)} "request to join an existing one"] "."]
   [:div.mb-2
    [:span.me-2 [create-cust-btn]]
    [join-cust-btn]]
   [:p
    "You can create one organization per user account." [:b.mx-1 "Creating an organization is free,"]
    "a credit card is not required.  Each organization gets" [:b.mx-1 free-credits " free credits"]
    "per month. "
    "One credit can be spent on one cpu minute, or one memory GB per minute. "
    "You can join an unlimited number of organizations.  See more details in "
    [:a {:href (tc/docs-url template/config) :target :_blank} "the documentation."]]
   [:p
    "After you have created or joined an organization, you can start " [:b "adding repositories "]
    "and unlock the full power of " [:i "MonkeyCI!"]]])

(defn orgs []
  (let [c (rf/subscribe [:user/orgs])]
    (when @c
      [:<>
       [:h3 [:span.me-2 co/overview-icon] "Your Linked Organizations"]
       [:p [:b "Welcome! "] "This screen shows all organizations linked to your user account."]
       (if (empty? @c)
         [no-orgs]
         [linked-orgs @c])])))

(defn user-home [u]
  (rf/dispatch [:home/initialize])
  [:<>
   [co/alerts [:user/alerts]]
   [orgs]])

(defn redirect-to-login []
  [:p "One moment, redirecting you to the login page"]
  (rf/dispatch [:route/goto :page/login]))

(defn page
  "Renders user home page, or redirects to login if user has not authenticated yet."
  []
  ;; TODO When a valid token was stored, use it to fetch user info
  (let [u (rf/subscribe [:login/user])]
    [l/default
     [:<>
      (if @u
        [user-home @u]
        [redirect-to-login])]]))

(defn- search-btn []
  (let [busy? (rf/subscribe [:org/searching?])]
    [:button.btn.btn-primary {:disabled @busy?} [:span.me-2 [co/icon :search]] "Search"]))

(defn- search-org []
  [:form
   {:on-submit (f/submit-handler [:org/search])}
   [:div.row
    [:div.col
     [:label.form-label {:for :org-search} "Search organization"]
     [:input.form-control.mb-3
      {:id :org-search
       :name :org-search
       :placeholder "Name or id"}]]]
   [:div.row
    [:div.col
     [:span.me-2 [search-btn]]
     [co/cancel-btn [:route/goto :page/root]]]]])

(defn- join-btn [{:keys [id]} disabled?]
  [:button.btn.btn-sm.btn-success
   {:on-click #(rf/dispatch [:org/join id])
    :disabled disabled?}
   [:span.me-2 [co/icon :arrow-right-square]] "Join"])

(defn- join-actions [{:keys [status] :as cust}]
  (case status
    :joined
    [:span.badge.text-bg-success
     {:title "You have already joined this organization"}
     "joined"]
    :pending
    [:span.badge.text-bg-warning
     {:title "Sending request to join"}
     "pending"]
    [join-btn cust (= :joining status)]))

(defn orgs-table []
  (letfn [(org-name [{:keys [id name joined?]}]
            (if joined?
              ;; Display link to go to org if already joined
              [:a {:href (r/path-for :page/org {:org-id id})} name]
              name))]
    [t/paged-table
     {:id :org/search-results
      :items-sub [:org/join-list]
      :columns [{:label "Name"
                 :value org-name}
                {:label "Actions"
                 :value join-actions}]}]))

(defn search-results []
  (let [r (rf/subscribe [:org/join-list])]
    (when @r
      (if (empty? @r)
        [:p "No matches found, you could try another query."]
        [:div
         [:p "Found " (count @r) " matching " (u/pluralize "org" (count @r)) "."]
         [orgs-table]]))))

(defn- request-status [{:keys [status]}]
  (let [cl (condp = status
             :pending  :bg-warning
             :approved :bg-success
             :rejected :bg-danger
             :bg-warning)]
    [:span {:class (str "badge " (name cl))} (some-> status (name))]))

(defn- join-requests-table []
  (letfn [(request-actions [jr]
            [:button.btn.btn-sm.btn-danger
             {:on-click #(rf/dispatch [:join-request/delete (:id jr)])}
             [:span {:title "Delete"} [co/icon :trash]]])]
    [t/paged-table
     {:id :user/join-requests
      :items-sub [:user/join-requests]
      :columns [{:label "Organization"
                 :value (comp :name :org)}
                {:label "Status"
                 :value request-status}
                {:label "Actions"
                 :value request-actions}]}]))

(defn join-requests []
  (rf/dispatch [:join-request/load])
  (let [r (rf/subscribe [:user/join-requests])]
    [:<>
     [:h3 "Pending Join Requests"]
     (if (nil? @r)
       [:p "Loading information..."]
       (if (empty? @r)
         [:p "No requests pending."]
         [join-requests-table]))]))

(defn page-join
  "Displays the 'join organization' page"
  []
  (rf/dispatch-sync [:org/join-init])
  (fn []
    [l/default
     [:div.row
      [:div.col-lg-6
       [:div.card
        [:div.card-body
         [:h3.card-title "Join Existing Organization"]
         [:p
          "On this page you can search for an organization and request to join it.  A user "
          "with administrator permissions for that organization can approve your request."]
         [co/alerts [:org/join-alerts]]
         [search-org]
         [:div.mt-2
          [search-results]]]]]
      [:div.col-lg-6
       [:div.card
        [:div.card-body
         [join-requests]]]]]]))
