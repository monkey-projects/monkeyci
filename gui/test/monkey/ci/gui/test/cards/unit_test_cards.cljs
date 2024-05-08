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
       :time "0.1735"}
      {:test-case "monkey.ci.test/other-test"
       :class-name "monkey.ci.test"
       :time "0.0414"}
      {:test-case "monkey.ci.test/failing"
       :class-name "monkey.ci.test"
       :time "0.069343"
       :failures
       [{:message "Test error"
         :type "assertion error: true?"
         :description "This is a longer description of the error"}]}]}]])

