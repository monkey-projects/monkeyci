(ns monkey.ci.gui.shadow-runner
  "Custom implementation of the kaocha cljs2 shadow runner.  Need to override the
   default one, because it doesn't support reagent 19."
  {:dev/always true}
  (:require [goog.dom :as gdom]
            [kaocha.cljs2.shadow-runner :as kcs]
            [lambdaisland.chui.ui :as ui]
            [reagent.dom.client :as rd]))

(defn render! [el]
  (ui/set-state-from-location)
  (rd/render el [ui/app]))

(defn ^:export init []
  (let [app (gdom/createElement "div")
        root (rd/create-root app)]
    (gdom/setProperties app #js {:id "chui-container"})
    (gdom/append js/document.body app)
    (render! root))
  (kcs/start))
