(ns monkey.ci.gui.test.fixtures
  (:require [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(def reset-db
  #?(:cljs {:before #(reset! app-db {})}))

(defn reset-re-frame []
  #?(:cljs (let [f (atom nil)]
             {:before (reset! f (rf/make-restore-fn))
              :after (@f)})))
