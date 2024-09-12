(ns monkey.ci.gui.test.cards.table-cards
  (:require [devcards.core :refer-macros [defcard-rg]]
            [monkey.ci.gui.table :as sut]
            [reagent.core]
            [re-frame.core :as rf]
            [re-frame.db :as rdb]))

(rf/reg-sub
 ::small-table
 (fn [_ _]
   [{:name "Albert Einstein"
     :profession "Physicist"}
    {:name "Friedrich Engels"
     :profession "Philosopher"}
    {:name "Pieter Breughel"
     :profession "Painter"}]))

(defcard-rg small-table
  "Table with small number of items"
  (let [id ::small-table]
    [sut/paged-table {:id id
                      :items-sub [::small-table]
                      :columns [{:label "Name"
                                 :value :name}
                                {:label "Profession"
                                 :value :profession}]}]))

(rf/reg-sub
 ::large-table
 (fn [_ _]
   (->> (range 85)
        (mapv (fn [n]
                {:name (str "User " (inc n))
                 :number (inc n)})))))

(defcard-rg large-table
  "Table with large number of items"
  (let [id ::large-table]
    [sut/paged-table {:id id
                      :items-sub [::large-table]
                      :columns [{:label "Id"
                                 :value :number}
                                {:label "Name"
                                 :value :name}]}]))

(rf/reg-sub
 ::loading?
 (constantly true))

(defcard-rg loading-table
  "Table that is still loading"
  (let [id ::loading-table]
    [sut/paged-table
     {:id        id
      :items-sub [::large-table]
      :loading   {:sub  [::loading?]
                  :rows 5}
      :columns   [{:label "Id"
                   :value :number}
                  {:label "Name"
                   :value :name}]}]))
