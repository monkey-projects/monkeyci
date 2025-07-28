(ns monkey.ci.gui.clipboard
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-sub
 ::current
 (fn [db _]
   (::current db)))

(rf/reg-event-fx
 ::clipboard-copy
 (fn [{:keys [db]} [_ v]]
   {:db (assoc db ::current v)
    :clipboard/set v}))

(rf/reg-fx
 :clipboard/set
 (fn [val]
   (.. js/navigator
       -clipboard
       (writeText val))))

(defn copy-handler
  "Creates an event handler that when invoked, copies `v` into the clipboard."
  [v]
  (u/link-evt-handler [::clipboard-copy v]))

(defn clipboard-copy
  "Renders an icon that, when clicked, copies the specified value into
   the clipboard.  The value is stored in the db, and whenever the value
   in db differs from the specified value, the icon changes back to the
   original value."
  [v desc]
  (let [c (rf/subscribe [::current])]
    [:a {:href "#"
         :on-click (copy-handler v)
         ;; TODO Use bootstrap tooltips
         :title desc}
     [co/icon (if (= v @c)
                :clipboard-check
                :clipboard)]]))
