(ns monkey.ci.gui.test.cards
  "Devcards entry point"
  (:require [devcards.core :as dc]
            [devcards.system :as ds]
            [react :as react]
            [reagent.dom :as rd]
            [reagent.dom.client :as rdc]
            ["highlight.js" :as hljs]
            ["marked" :as marked]))

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

(defn- renderer
  "Reworked the original devcards renderer for reagent 19"
  [root _]
  (rdc/render
   root
   ;; FIXME Even though this renders, as soon as you click a link, it throws another error
   (react/createElement ds/DevcardsRoot)))

(defn ^:export init []
  ;; No longer works with reagent 19
  #_(dc/start-devcard-ui!)
  (ds/render-base-if-necessary!)
  (let [root (rdc/create-root (ds/devcards-app-node))]
    (ds/start-ui-with-renderer
     dc/devcard-event-chan
     (partial renderer root))
    (dc/register-figwheel-listeners!)))

(defn ^:dev/after-load reload []
  (init))
