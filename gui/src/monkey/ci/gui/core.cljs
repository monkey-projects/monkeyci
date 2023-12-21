(ns monkey.ci.gui.core
  (:require [monkey.ci.gui.events]
            [monkey.ci.gui.login.views :as lv]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.pages :as p]
            [monkey.ci.gui.routing :as routing]
            [monkey.ci.gui.utils :as u]
            [reagent.core :as rc]
            [reagent.dom.client :as rd]
            [re-frame.core :as rf]))

(defonce app-root (atom nil))

(defn ^:dev/after-load reload []
  (when @app-root
    (rf/clear-subscription-cache!)
    (rd/render @app-root [p/render])))

(defn init []
  (let [root (rd/create-root (.getElementById js/document "root"))]
    (reset! app-root root)
    (routing/start!)
    (rf/dispatch-sync [:initialize-db])
    (m/init)
    (reload)))
