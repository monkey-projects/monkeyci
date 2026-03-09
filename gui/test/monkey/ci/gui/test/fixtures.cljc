(ns monkey.ci.gui.test.fixtures
  (:require [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(def reset-db
  #?(:cljs {:before #(reset! app-db {})}))

(def restore-rf
  (let [r (atom nil)]
    {:before #(reset! r (rf/make-restore-fn))
     :after #(@r)}))

(def admin-router
  (let [r (atom nil)]
    {:before (fn []
               (reset! r @r/router)
               (reset! r/router r/admin-router))
     :after #(reset! r/router @r)}))
