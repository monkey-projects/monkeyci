(ns monkey.ci.gui.test.scenes.pagination-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.table :as sut]
            [re-frame.core :as rf]))

(defscene small-pagination
  "Pagination for low number of pages"
  [sut/render-pagination ::test 3 1])

(defscene first-page
  "First page should disable prev btn"
  [sut/render-pagination ::test 3 0])

(defscene last-page
  "Last page should disable next btn"
  [sut/render-pagination ::test 3 2])

(defscene large-pagination
  "Pagination for large number of pages"
  [sut/render-pagination ::test 100 20])

(defscene large-pagination-low-current
  "Pagination for large number of pages with low current"
  [sut/render-pagination ::test 100 1])

(defscene large-pagination-high-current
  "Pagination for large number of pages with high current"
  [sut/render-pagination ::test 100 98])

(defscene active-low-count
  "Active pagination with low number of pages"
  (let [id ::low-count]
    (rf/dispatch [:pagination/set id {:count 3 :current 1}])
    [sut/pagination id]))

(defscene active-high-count
  "Active pagination with high number of pages"
  (let [id ::high-count]
    (rf/dispatch [:pagination/set id {:count 20 :current 10}])
    [sut/pagination id]))
