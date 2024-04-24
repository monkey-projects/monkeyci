(ns monkey.ci.gui.subs
  "Subs that are globally useful"
  (:require [monkey.ci.gui.customer.subs]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-sub
 :breadcrumb/path
 :<- [:route/current]
 :<- [:customer/info]
 (fn [[r ci] _]
   ;; Maybe panels should "register" themselves in the breadcrumb instead, by dispatching an event?
   (let [{:keys [customer-id repo-id build-id job-id] :as p} (get-in r [:parameters :path])]
     (cond-> [{:url "/"
               :name "Home"}]
       customer-id
       (conj {:url (r/path-for :page/customer (select-keys p [:customer-id]))
              :name (:name ci)})
       
       repo-id
       (conj {:url (r/path-for :page/repo (select-keys p [:customer-id :repo-id]))
              :name (->> ci
                         :repos
                         (u/find-by-id repo-id)
                         :name)})

       build-id
       (conj {:url (r/path-for :page/build (select-keys p [:customer-id :repo-id :build-id]))
              :name build-id})))))
