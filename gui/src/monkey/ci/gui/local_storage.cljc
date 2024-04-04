(ns monkey.ci.gui.local-storage
  "Provides fx and cofx to access browser local storage"
  (:require [clojure.edn :as edn]
            [re-frame.core :as rf]))

(rf/reg-fx
 :local-storage
 (fn [[id value]]
   #?(:cljs (when (exists? js/localStorage)
              (.setItem js/localStorage (str id) (pr-str value)))
      :clj nil)))

(rf/reg-cofx
 :local-storage
 (fn [cofx id]
   (assoc cofx :local-storage #?(:cljs (when (exists? js/localStorage)
                                         (edn/read-string (.getItem js/localStorage (str id))))
                                 :clj nil))))
