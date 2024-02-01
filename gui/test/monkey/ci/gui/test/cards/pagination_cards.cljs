(ns monkey.ci.gui.test.cards.pagination-cards
  (:require [devcards.core :refer-macros [defcard-rg]]
            [monkey.ci.gui.table :as sut]
            [reagent.core]
            [re-frame.core :as rf]
            [re-frame.db :as rdb]))

(defcard-rg small-pagination
  "Pagination for low number of pages"
  [sut/render-pagination ::test 3 1])

(defcard-rg first-page
  "First page should disable prev btn"
  [sut/render-pagination ::test 3 0])

(defcard-rg last-page
  "Last page should disable next btn"
  [sut/render-pagination ::test 3 2])

(defcard-rg large-pagination
  "Pagination for large number of pages"
  [sut/render-pagination ::test 100 20])

(defcard-rg large-pagination-low-current
  "Pagination for large number of pages with low current"
  [sut/render-pagination ::test 100 1])

(defcard-rg large-pagination-high-current
  "Pagination for large number of pages with high current"
  [sut/render-pagination ::test 100 98])

(defcard-rg active-low-count
  "Active pagination with low number of pages"
  (let [id ::low-count]
    (rf/dispatch [:pagination/set id {:count 3 :current 1}])
    [sut/pagination id]))

(defcard-rg active-high-count
  "Active pagination with high number of pages"
  (let [id ::high-count]
    (rf/dispatch [:pagination/set id {:count 20 :current 10}])
    [sut/pagination id]))
