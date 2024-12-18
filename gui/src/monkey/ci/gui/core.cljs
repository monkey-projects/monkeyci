(ns monkey.ci.gui.core
  (:require [day8.re-frame.http-fx]
            [monkey.ci.gui.download]
            [monkey.ci.gui.events]
            [monkey.ci.gui.login.views :as lv]
            [monkey.ci.gui.pages :as p]
            [monkey.ci.gui.server-events]
            [monkey.ci.gui.utils :as u]
            [reagent.core :as rc]
            [reagent.dom.client :as rd]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(defonce app-root (atom nil))

(defn- get-app-root! []
  (swap! app-root (fn [r]
                    (or r (rd/create-root (.getElementById js/document "root"))))))

(defn ^:dev/after-load reload []
  ;; Creating the root multiple times gives a react warning on reload.  However, if we
  ;; keep track of the existing root instead, re-frame subs give problems, that's why
  ;; the pages ns is on "always reload".
  (let [root (get-app-root!)]
    (rf/clear-subscription-cache!)
    (rd/render root [p/render])))
