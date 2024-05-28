(ns monkey.ci.gui.test.cards.unit-test-cards
  (:require [devcards.core :refer-macros [defcard-rg]]
            [clojure.string :as cs]
            [monkey.ci.gui.test-results :as sut]
            [reagent.core]
            [re-frame.core :as rf]
            [re-frame.db :as rdb]))

(defcard-rg single-suite
  "Unit tests for single suite"
  [sut/test-results
   [{:name "unit"
     :test-cases
     [{:test-case "monkey.ci.test/some-test"
       :class-name "monkey.ci.test"
       :time 0.1735}
      {:test-case "monkey.ci.test/other-test"
       :class-name "monkey.ci.test"
       :time 0.0414}
      {:test-case "monkey.ci.test/failing"
       :class-name "monkey.ci.test"
       :time 0.069343
       :failures
       [{:message "Test error"
         :type "assertion error: true?"
         :description "This is a longer description of the error"}]}]}]])

(defn- gen-test-case [n]
  {:test-case (str "test-" n)
   :time (* (rand) 10)})

(defcard-rg timing-chart-small-single-suite
  "Timing chart for single suit with low number of tests"
  [sut/timing-chart
   :small-single-suite
   [{:name "small"
     :test-cases
     (->> (range 3)
          (map (comp gen-test-case inc)))}]])

(defcard-rg timing-chart-large-single-suite
  "Timing chart for single suit with high number of tests"
  [sut/timing-chart
   :large-single-suite
   [{:name "large"
     :test-cases
     (->> (range 200)
          (map (comp gen-test-case inc)))}]])
