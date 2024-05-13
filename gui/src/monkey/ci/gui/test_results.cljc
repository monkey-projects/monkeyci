(ns monkey.ci.gui.test-results
  "Displays test results"
  (:require [monkey.ci.gui.components :as co]))

(def success? (comp (partial every? empty?) (juxt :errors :failures)))

(defn- test-row [{:keys [test-case class-name time] :as tc}]
  [:tr
   [:td test-case]
   [:td (co/build-result (if (success? tc) "success" "failure")) ]
   [:td time "s"]])

(defn- suite-rows [suite]
  (map test-row (:test-cases suite)))

(defn test-results [suites]
  [:table.table.table-striped
   [:thead
    [:tr
     [:th "Test case"]
     [:th "Result"]
     [:th "Elapsed"]]]
   (->> suites
        (mapcat suite-rows)
        (into [:tbody]))])
