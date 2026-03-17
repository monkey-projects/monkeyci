(ns monkey.ci.gui.test.fixtures
  (:require [monkey.ci.gui.admin.routing :as ar]
            [monkey.ci.gui.main.routing :as mr]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(def reset-db
  #?(:cljs {:before #(reset! app-db {})}))

(def restore-rf
  (let [r (atom nil)]
    {:before #(reset! r (rf/make-restore-fn))
     :after #(@r)}))

(defn with-router [new-router]
  (let [r (atom nil)]
    {:before (fn []
               ;; Save old router
               (reset! r @r/router)
               (reset! r/router new-router))
     :after #(reset! r/router @r)}))

(def main-router
  (with-router mr/main-router))

(def admin-router
  (with-router ar/admin-router))
