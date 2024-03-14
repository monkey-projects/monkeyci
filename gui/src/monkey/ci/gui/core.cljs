(ns monkey.ci.gui.core
  (:require [monkey.ci.gui.events]
            [monkey.ci.gui.login.views :as lv]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.pages :as p]
            [monkey.ci.gui.routing :as routing]
            [monkey.ci.gui.server-events]
            [monkey.ci.gui.utils :as u]
            [reagent.core :as rc]
            [reagent.dom.client :as rd]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(defonce app-root (atom nil))

(defn ^:dev/after-load reload []
  (when @app-root
    (println "Reloading application")
    ;; If we do this, parts of the application no longer work after reloading
    ;; But also we need to refresh when we modify subs...
    #_(rf/clear-subscription-cache!)
    (rd/render @app-root [p/render])))

(defn init []
  (let [root (rd/create-root (.getElementById js/document "root"))]
    (reset! app-root root)
    (routing/start!)
    (rf/dispatch-sync [:initialize-db])
    (m/init)
    (reload)))
