(ns monkey.ci.gui.local-storage
  "Provides fx and cofx to access browser local storage"
  (:require [clojure.edn :as edn]
            [re-frame.core :as rf]))

(defn local-storage-enabled? []
  #?(:cljs (exists? js/localStorage)
     :clj false))

(rf/reg-fx
 :local-storage
 (fn [[id value]]
   #?(:cljs (when (local-storage-enabled?)
              (.setItem js/localStorage (str id) (pr-str value)))
      :clj nil)))

(rf/reg-cofx
 :local-storage
 (fn [cofx id]
   (assoc cofx :local-storage #?(:cljs (when (local-storage-enabled?)
                                         (edn/read-string (.getItem js/localStorage (str id))))
                                 :clj nil))))
