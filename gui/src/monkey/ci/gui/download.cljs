(ns monkey.ci.gui.download
  "JS code for creating a hidden download link.  This is necessary when you want to
   download a file over ajax."
  (:require [re-frame.core :as rf]))

(defn make-download-link [file blob]
  (let [el (-> js/document (.createElement "a"))]
    (set! (.-href el) (-> js/window (.-URL) (.createObjectURL blob)))
    (set! (.-download el) file)
    (.click el)))

(rf/reg-fx
 :download-link
 (fn [[file blob]]
   (make-download-link file blob)))
