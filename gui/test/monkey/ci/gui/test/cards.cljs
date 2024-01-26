(ns monkey.ci.gui.test.cards
  "Devcards entry point"
  (:require [devcards.core :as dc :include-macros true]
            ["highlight.js" :as hljs]
            ["marked" :as marked]
            [monkey.ci.gui.test.cards.table-cards]
            [monkey.ci.gui.test.cards.timer-cards]))

#_(defn marked-parse [x]
  (.parse marked x))

(defn export-symbols []
  ;; devcards uses global DevcardsSyntaxHighlighter and DevcardsMarked
  ;; ex. https://github.com/bhauman/devcards/blob/master/src/devcards/util/markdown.cljs#L28
  ;; therefore we need to define them
  (js/goog.exportSymbol "DevcardsSyntaxHighlighter" hljs)
  (js/goog.exportSymbol "DevcardsMarked" marked))

(export-symbols)

#_(defn ^:dev/after-load reload []
  (println "Exporting symbols again")
  (export-symbols))
  
(defn ^:export init []
  (dc/start-devcard-ui!))
