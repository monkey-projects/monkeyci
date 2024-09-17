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

(defcard-rg long-table-cells
  "Table with cells that contain long value strings.  Should they be truncated or not?  Can they be truncated?"
  [sut/render-table
   [{:label "Name"
     :value :name}
    {:label "Description"
     :value :description}]
   [{:name "Short name"
     :description "Lorem ipsum odor amet, consectetuer adipiscing elit. Praesent ipsum quis praesent; mauris nam mattis egestas egestas donec. Ultricies molestie vitae mus neque lacinia mauris tristique fusce. Atortor et praesent molestie molestie vulputate eleifend sit. Dignissim faucibus ut et consectetur lectus feugiat libero integer. Auctor senectus semper primis semper amet justo magna natoque enim."}
    {:name "Lorem ipsum odor amet, consectetuer adipiscing elit"
     :description "Short description"}]
   {}])

(defcard-rg row-click
  "Table with a row-click event handler"
  [sut/render-table
   [{:label "ID"
     :value :id}
    {:label "Name"
     :value :name}]
   [{:id "first"
     :name "First item"}
    {:id "second"
     :name "Second item"}]
   {:class [:table-hover]
    :on-row-click #(js/alert (str "Row clicked:" %))}])
