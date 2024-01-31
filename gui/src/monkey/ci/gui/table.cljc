(ns monkey.ci.gui.table
  "Table functionality"
  (:require [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn nav-link [item-opts link-opts content]
  [:li.page-item item-opts
   [:a.page-link link-opts content]])

(defn prev-btn [id & [opts]]
  [nav-link
   opts
   {:href "#"
    :aria-label "Previous"
    :on-click (u/link-evt-handler [:pagination/prev id])}
   [:span {:aria-hidden true} (u/unescape-entity "&laquo;")]])

(defn next-btn [id & [opts]]
  [nav-link
   opts
   {:href "#"
    :aria-label "Next"
    :on-click (u/link-evt-handler [:pagination/next id])}
   [:span {:aria-hidden true} (u/unescape-entity "&raquo;")]])

(def max-pages-to-show 5)

(defn render-pagination
  "Renders pagination component for `n` pages with `c` the current page."
  [id n c]
  (letfn [(render-page-item [i]
            [nav-link
             (when (= i c)
               {:class :active
                :aria-current "page"})
             {:href "#"
              :on-click (u/link-evt-handler [:pagination/goto id i])}
             (str (inc i))])
          (pages []
            (if (> n max-pages-to-show)
              (let [e [nav-link {:class :disabled} (u/unescape-entity "&hellip;")]
                    v (int (/ max-pages-to-show 2))]
                ;; Add ellipsis where necessary
                (concat [(when (pos? (- c v)) (render-page-item 0))
                         (when (pos? (- c v 1)) e)]
                        (map render-page-item (range (max 0 (- c v)) (min n (+ c v 1))))
                        [(when (< (+ c v 2) n) e)
                         (when (< (+ c v 1) n) (render-page-item (dec n)))]))
              (map render-page-item (range n))))]
    [:nav
     (into [:ul.pagination]
           (concat [[prev-btn id
                     (when (= 0 c) {:class :disabled})]]
                   (pages)
                   [[next-btn id
                     (when (= n (inc c)) {:class :disabled})]]))]))

(defn- valid-pagination? [{:keys [count current]}]
  (and (<= 0 current) (< current count)))

(defn set-pagination [db id p]
  (cond-> db
    (valid-pagination? p)
    (assoc-in [::pagination id] p)))

(defn get-pagination [db id]
  (get-in db [::pagination id]))

(defn- update-current-page [db id f]
  (set-pagination db id (update (get-pagination db id) :current f)))

(rf/reg-sub
 :pagination/info
 (fn [db [_ id]]
   (get-pagination db id)))

(rf/reg-event-db
 :pagination/set
 (fn [db [_ id p]]
   (set-pagination db id p)))

(rf/reg-event-db
 :pagination/next
 (fn [db [_ id]]
   (update-current-page db id inc)))

(rf/reg-event-db
 :pagination/prev
 (fn [db [_ id]]
   (update-current-page db id dec)))

(rf/reg-event-db
 :pagination/goto
 (fn [db [_ id idx]]
   (update-current-page db id (constantly idx))))

(defn pagination
  "Displays pagination component with given id.  The id is used to store/retrieve
   information in db."
  [id]
  (let [pi (rf/subscribe [:pagination/info id])]
    (when @pi
      [render-pagination id (:count @pi) (:current @pi)])))

(defn paged-table [conf]
  ;; TODO
  )
