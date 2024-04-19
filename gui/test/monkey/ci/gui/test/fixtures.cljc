(ns monkey.ci.gui.test.fixtures
  (:require [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(def reset-db
  #?(:cljs {:before #(reset! app-db {})}))

(def restore-rf
  (let [r (atom nil)]
    {:before (reset! r (rf/make-restore-fn))
     :after (@r)}))
