(ns monkey.ci.gui.customer.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.customer.events]
            [monkey.ci.gui.customer.subs]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.repo.views :as rv]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.tabs :as tabs]
            [re-frame.core :as rf]))

(defn- show-repo [c p r]
  [:div.repo.card-body
   [:div.float-start
    [:b {:title (:id r)} (:name r)]
    [:p "Url: " [:a {:href (:url r)} (:url r)]]]
   [:a.btn.btn-primary.float-end
    {:href (r/path-for :page/repo {:customer-id (:id c)
                                   :repo-id (:id r)})}
    [co/icon :three-dots-vertical] " Details"]])

(defn- show-project [cust [p repos]]
  (->> repos
       (sort-by :name)
       (map (partial show-repo cust p))
       (into
        [:div.project.card.mb-3
         [:div.card-header
          [:h5.card-title p]]])))

(defn- project-lbl [r]
  (->> (:labels r)
       (filter (comp (partial = "project") :name))
       (map :value)
       (first)))

(defn- add-repo-btn [id]
  [:a.btn.btn-outline-dark.me-2
   {:href (r/path-for :page/add-repo {:customer-id id})
    :title "Link an existing GitHub repository"}
   [:span.me-1 [co/icon :github]] "Follow Repository"])

(defn- params-btn [id]
  [:a.btn.btn-outline-primary.me-2
   {:href (r/path-for :page/customer-params {:customer-id id})
    :title "Configure build parameters"}
   [:span.me-2 [co/icon :gear]] "Parameters"])

(defn- customer-actions [id]
  [:<>
   [add-repo-btn id]
   [params-btn id]
   [co/reload-btn [:customer/load id]]])

(defn- customer-header []
  (let [c (rf/subscribe [:customer/info])]
    [:div.clearfix.mb-3
     [:h3.float-start (:name @c)]
     [:span.float-end
      [customer-actions (:id @c)]]]))

(defn- customer-repos
  "Displays a list of customer repositories, grouped by project"
  []
  (let [c (rf/subscribe [:customer/info])]
    (if (empty? (:repos @c))
      [:p "No repositories configured for this customer.  You can start by"
       [:a.mx-1 {:href (r/path-for :page/add-repo {:customer-id (:id @c)})} "following one."]]
      (->> (:repos @c)
           ;; TODO Allow grouping by any custom label
           (group-by project-lbl)
           (sort-by first)
           (map (partial show-project @c))
           (into [:<>
                  [:p "Repository overview, grouped by project."]])))))

(defn- recent-builds [id]
  (rf/dispatch [:customer/load-recent-builds id])
  (fn [id]
    (let [loaded? (rf/subscribe [:loader/loaded? db/recent-builds])
          recent (rf/subscribe [:customer/recent-builds])]
      (if (and @loaded? (empty? @recent))
        [:p "No recent builds found for this customer."]
        [:<>
         (if @loaded?
           [:p "Recent builds for all repositories."]
           [:p "Loading recent builds for all repositories..."])
         [t/paged-table
          {:id ::recent-builds
           :items-sub [:customer/recent-builds]
           :columns (concat [{:label "Repository"
                              :value (fn [b]
                                       [:a {:href (r/path-for :page/repo b)} (get-in b [:repo :name])])}]
                            rv/table-columns)}]]))))

(defn- overview-tabs
  "Displays tab pages for various customer overview screens"
  [id]
  [tabs/tabs ::overview
   [{:header "Repositories"
     :contents [customer-repos]
     :current? true}
    {:header "Recent Builds"
     :contents [recent-builds id]}]])

(defn- customer-details [id]
  [:<>
   [customer-header]
   [overview-tabs id]])

(defn page
  "Customer overview page"
  [route]
  (let [id (-> route (r/path-params) :customer-id)]
    (rf/dispatch [:customer/init id])
    (l/default
     [:div
      [co/alerts [:customer/alerts]]
      [customer-details id]])))

(defn- repo-table []
  (letfn [(name+url [{:keys [name html-url]}]
            [:a {:href html-url :target "_blank"} name])
          (visibility [{:keys [visibility]}]
            [:span.badge {:class (if (= "public" visibility)
                                   :text-bg-success
                                   :text-bg-warning)}
             visibility])
          (actions [{:keys [:monkeyci/watched?] :as repo}]
            (if watched?
              [:button.btn.btn-sm.btn-danger
               {:on-click #(rf/dispatch [:repo/unwatch (:monkeyci/repo repo)])}
               [:span.me-1.text-nowrap [co/icon :stop-circle-fill]] "Unwatch"]
              [:button.btn.btn-sm.btn-primary
               {:on-click #(rf/dispatch [:repo/watch repo])}
               [:span.me-1.text-nowrap [co/icon :binoculars-fill]] "Watch"]))]
    [t/paged-table
     {:id ::repos
      :items-sub [:customer/github-repos]
      :columns [{:label "Name"
                 :value name+url}
                {:label "Owner"
                 :value (comp :login :owner)}
                {:label "Description"
                 :value :description}
                {:label "Visibility"
                 :value visibility}
                {:label "Actions"
                 :value actions}]}]))

(defn add-repo-page
  []
  (let [route (rf/subscribe [:route/current])]
    (rf/dispatch [:customer/load-github-repos])
    (l/default
     [:<>
      [:h3 "Add Repository to Watch"]
      [co/alerts [:customer/repo-alerts]]
      [repo-table]
      [:a {:href (r/path-for :page/customer (r/path-params @route))} "Back to customer"]])))

(defn page-new
  "New customer page"
  []
  (l/default
   [:<>
    [:h3 "New Customer"]
    [:form.mb-3
     {:on-submit (f/submit-handler [:customer/create])}
     [:div.mb-3
      [:label.form-label {:for :name} "Name"]
      [:input#name.form-control {:aria-describedby :name-help :name :name}]
      [:div#name-help.form-text "The customer name.  We recommend to make it as unique as possible."]]
     [:div
      [:button.btn.btn-primary.me-2 {:type :submit} [:span.me-2 [co/icon :save]] "Create Customer"]
      [co/cancel-btn [:route/goto :page/root]]]]
    [co/alerts [:customer/create-alerts]]]))
