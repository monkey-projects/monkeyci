(ns monkey.ci.gui.test.cards.accordion-cards
  (:require [devcards.core :refer-macros [defcard-rg]]
            [monkey.ci.gui.components :as sut]
            [reagent.core]
            [re-frame.db :as rdb]))

(defcard-rg accordion-basic
  "Basic accordion with one tab open"
  [sut/accordion
   ::accordion-basic
   [{:title "Item 1"
     :collapsed true
     :contents [:p "This is the first item"]}
    {:title "Item 2"
     :collapsed false
     :contents [:p "This is the second item"]}
    {:title "Item 3"
     :collapsed true
     :contents [:p "This is the third item"]}]])

(defcard-rg accordion-with-body
  "Accordion with more complicated body"
  [sut/accordion
   ::accordion-with-body
   [{:title "Overview"
     :contents
     [:div
      [:h2 "This is the overview"]
      [:ul
       [:li "Here we can"]
       [:li "Add more complicated things"]]]}
    {:title [:span "Details" [:span.ms-1.badge.text-bg-success "success"]]
     :collapsed true
     :contents
     [:div
      [:h2 "These are the details"]
      [:ul
       [:li "Here we can"]
       [:li "Add more complicated things"]]]}]])
