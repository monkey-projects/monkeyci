(ns monkey.ci.gui.test.scenes.table-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.table :as sut]
            [re-frame.core :as rf]))

(rf/reg-sub
 ::small-table
 (fn [_ _]
   [{:name "Albert Einstein"
     :profession "Physicist"}
    {:name "Friedrich Engels"
     :profession "Philosopher"}
    {:name "Pieter Breughel"
     :profession "Painter"}]))

(defscene small-table
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

(defscene large-table
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

(defscene loading-table
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

(defscene long-table-cells
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

(defscene row-click
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

(defscene sorted-table
  "Table with sorted column"
  [sut/render-table
   [{:label "Index"
     :value :idx
     :sorting :asc}
    {:label "Name"
     :value :name}]
   (->> (range 10)
        (map (fn [idx]
               {:idx (inc idx)
                :name (str "Item " (inc idx))})))
   {}])

(rf/reg-sub
 ::sortable-table-items
 (fn [_ _]
   (->> (range 10)
        (map (fn [idx]
               {:idx (inc idx)
                :name (str "Item " (inc idx))})))))

(defscene sortable-table
  "Table that allows the user to change sorting"
  [sut/paged-table
   {:id ::sortable-table
    :items-sub [::sortable-table-items]
    :columns [{:label "Index"
               :value :idx
               :sorter (sut/prop-sorter :idx)}
              {:label "Name"
               :value :name}]}])
