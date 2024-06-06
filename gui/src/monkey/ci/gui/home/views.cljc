(ns monkey.ci.gui.home.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.utils :as u]
            [monkey.ci.gui.home.events]
            [monkey.ci.gui.home.subs]
            [re-frame.core :as rf]))

(defn- create-cust-btn []
  [:a.btn.btn-primary
   {:href (r/path-for :page/customer-new)}
   [:span.me-2 [co/icon :plus-square]] "Create New Customer"])

(defn- join-cust-btn []
  [:a.btn.btn-primary
   {:href (r/path-for :page/customer-join)}
   [:span.me-2 [co/icon :arrow-right-square]] "Join Customer"])

(defn- cust-item [{:keys [id name owner?]}]
  [:li
   [:a {:href (r/path-for :page/customer {:customer-id id})}
    name
    (when owner?
      [:span.badge.text-bg-success.ms-2 "owner"])]])

(defn- linked-customers [cust]
  [:div
   (->> (map cust-item cust)
        (into [:ul]))
   [join-cust-btn]])

(defn customers []
  (let [c (rf/subscribe [:user/customers])]
    (when @c
      [:<>
       [:h3 "Your Linked Customers"]
       (if (empty? @c)
         [:<>
          [:p "No customers have been linked to your account.  You could either "
           [:a {:href (r/path-for :page/customer-new)} "create a new one"]
           " or "
           [:a {:href (r/path-for :page/customer-join)} "request to join an existing one"] "."]
          [:div
           [:span.me-2 [create-cust-btn]]
           [join-cust-btn]]]
         [linked-customers @c])])))

(defn user-home [u]
  (rf/dispatch [:user/load-customers])
  [:<>
   [co/alerts [:user/alerts]]
   [customers]])

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
  (let [busy? (rf/subscribe [:customer/searching?])]
    [:button.btn.btn-primary {:disabled @busy?} [:span.me-2 [co/icon :search]] "Search"]))

(defn- search-customer []
  [:form
   {:on-submit (f/submit-handler [:customer/search])}
   [:div.row
    [:div.col-lg-6
     [:label.form-label {:for :customer-search} "Search customer"]
     [:input.form-control.mb-3
      {:id :customer-search
       :name :customer-search
       :placeholder "Name or id"}]]]
   [:div.row
    [:div.col
     [search-btn]]]])

(defn- join-btn [{:keys [id]}]
  [:button.btn.btn-sm.btn-success
   {:on-click #(rf/dispatch [:customer/join id])}
   [:span.me-2 [co/icon :arrow-right-square]] "Join"])

(defn- join-actions [{:keys [joined?] :as cust}]
  (if joined?
    [:span.badge.text-bg-success
     {:title "You have already joined this customer"}
     "joined"]
    [join-btn cust]))

(defn customers-table []
  (letfn [(customer-name [{:keys [id name joined?]}]
            (if joined?
              ;; Display link to go to customer if already joined
              [:a {:href (r/path-for :page/customer {:customer-id id})} name]
              name))]
    [t/paged-table
     {:id :customer/search-results
      :items-sub [:customer/join-list]
      :columns [{:label "Name"
                 :value customer-name}
                {:label "Actions"
                 :value join-actions}]}]))

(defn search-results []
  (let [r (rf/subscribe [:customer/join-list])]
    (when @r
      (if (empty? @r)
        [:p "No matches found, you could try another query."]
        [:div
         [:p "Found " (count @r) " matching " (u/pluralize "customer" (count @r)) "."]
         [customers-table]]))))

(defn page-join
  "Displays the 'join customer' page"
  []
  [l/default
   [:<>
    [:h3 "Join Existing Customer"]
    [:p
     "On this page you can search for a customer and request to join it.  A user "
     "with administrator permissions for that customer can approve your request."]
    [co/alerts [:customer/join-alerts]]
    [search-customer]
    [:div.mt-2
     [search-results]]]])
