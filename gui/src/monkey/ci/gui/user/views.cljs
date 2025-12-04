(ns monkey.ci.gui.user.views
  "User preferences views"
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.tabs :as tabs]
            [re-frame.core :as rf]))

(defn- form-control [id lbl val & [input-opts]]
  [:div.row
   [:div.col-2
    [:label.form-label {:for id} lbl]]
   [:div.col-10
    [:input.form-control
     (merge {:name id
             :id id
             :value val}
            input-opts)]]])

(defn general-page []
  (let [u (rf/subscribe [:login/user])]
    [:div.flex-fill
     [co/page-title [:span.me-2 [co/icon :person-fill-gear]] "General Settings"]
     [:form.d-flex.flex-column.gap-2
      [form-control :name "Name" (:name @u) {:readonly true}]
      [form-control :name "E-mail" (:email @u)]]]))

(defn tabs [id]
  [tabs/settings-tabs
   [{:id :general
     :header "General"
     :link :page/user}
    #_{:id :tokens
       :header "API Tokens"
       :link "todo"}]
   id])

(defn overview [r]
  (l/default
   [:<>
    [:div.d-flex.gap-2
     [tabs :general]
     [general-page]]]))
