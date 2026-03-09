(ns monkey.ci.gui.table
  "Table functionality.  The main workhorse here is `paged-table`, which renders a component
   that takes its values from a sub and renders them according to the configured columns.
   It also allows sorting."
  (:require [clojure.math :as cm]
            [medley.core :as mc]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(comment
  [paged-table
   {:id ::test-table
    :items-sub [:test/items]
    :columns [{:label "Id"
               :value :id}
              {:label "Name"
               :value (comp :name :org)}]
    :loading {:sub [:test/loading?]
              :rows 5}}])

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
    (when (and @pi (pos? (:count @pi)))
      [render-pagination id (:count @pi) (:current @pi)])))

(defn get-sorting [db table-id]
  (get-in db [::sorting table-id]))

(defn set-sorting [db table-id s]
  (assoc-in db [::sorting table-id] s))

(defn update-sorting [db table-id f & args]
  (apply update-in db [::sorting table-id] f args))

(rf/reg-sub
 :table/sorting
 (fn [db [_ id]]
   (get-sorting db id)))

(rf/reg-event-db
 :table/sorting-toggled
 (fn [db [_ id new-idx]]
   (update-sorting db id (fn [{:keys [col-idx sorting]}]
                           {:col-idx new-idx
                            :sorting (if (and (= col-idx new-idx)
                                              (= :asc sorting))
                                       :desc
                                       :asc)}))))

(defn- render-thead [id columns]
  (letfn [(with-sorting [{:keys [sorting]} lbl]
            (case sorting
              :asc [:<> [:span.me-1 lbl] co/sort-down-icon]
              :desc [:<> [:span.me-1 lbl] co/sort-up-icon]
              lbl))
          (render-col [idx {l :label :keys [sorter] :as col}]
            [:th {:scope :col}
             ;; If a sorter is defined, make the label clickable
             (with-sorting
               col
               (cond->> l
                 (and sorter id)
                 (into [:a.link-dark
                        {:href ""
                         :on-click (u/link-evt-handler
                                    [:table/sorting-toggled id idx])}])))])]
    [:thead
     (->> columns
          (map-indexed render-col)
          (into [:tr]))]))

(defn- render-tbody [cols items {:keys [on-row-click]}]
  (letfn [(td? [x]
            (and (keyword? x) (re-matches #"^td\.?.*" (name x))))
          (render-cell [v]
            ;; Allow rendering functions to define their own td
            (if (and (vector? v) (td? (first v)))
              v
              [:td v]))
          (render-item [it]
            (->> cols
                 (map (fn [{:keys [value]}]
                        (value it)))
                 (map render-cell)
                 (into [:tr (when on-row-click
                              {:on-click #(on-row-click it)})])))]
    (->> items
         (map render-item)
         (into [:tbody]))))

(defn- render-loading [columns n-rows]
  [:<>
   [:table.table
    (render-thead nil columns)
    (render-tbody (mapv #(assoc % :value (fn [_] [:div.placeholder-glow [:span.w-75.placeholder "x"]]))
                        columns)
                  (repeat n-rows {})
                  {})]])

(defn- render-table [columns items opts]
  [:table.table.table-striped
   (select-keys opts [:class])
   (render-thead (:id opts) columns)
   (render-tbody columns items opts)])

(defn sorter-fn
  "Creates a default sorter fn, that invokes the target sorter when ascending,
   and reverses the result when descending."
  [target-fn]
  (fn [sorting]
    (fn [items]
      (cond-> (target-fn items)
        (= :desc sorting) reverse))))

(defn prop-sorter
  "Simple sorter for a single property"
  [prop]
  (sorter-fn (partial sort-by prop)))

(defn invoke-sorter [sorter sorting items]
  ((sorter sorting) items))

(defn apply-sorting
  "Sort items according to sorter defined on the respective column"
  [sorting cols items]
  (let [sorter (when (not-empty sorting)
                 (some-> (nth cols (:col-idx sorting))
                         :sorter))]
    (cond->> items
      sorter (invoke-sorter sorter (:sorting sorting)))))

(defn- mark-sorting
  "Indicates current sorting settings on the columns"
  [sorting cols]
  (cond->> cols
    (not-empty sorting)
    (map-indexed (fn [idx col]
                   (cond-> col
                     true (dissoc :sorting)
                     (= idx (:col-idx sorting)) (assoc :sorting (:sorting sorting)))))))

(defn paged-table
  "Table component with pagination.  The `items-sub` provides the items for the
   table.  The `columns` is a list of column configurations.  If `loading` is
   provided, it can hold a sub that indicates whether the table is loading, and
   a number of placeholder rows that should be displayed in that case."
  [{:keys [id items-sub columns page-size loading]
    :or {page-size 10}
    :as opts}]
  ;; TODO Add support for paginated requests (i.e. dispatch an event
  ;; when navigating to another page)
  (let [loading? (or (some-> loading :sub rf/subscribe deref)
                     false)
        sorting (rf/subscribe [:table/sorting id])]
    (if loading?
      (render-loading columns (get loading :rows 5))
      (when-let [items (rf/subscribe items-sub)]
        (let [pag (rf/subscribe [:pagination/info id])
              pc (int (cm/ceil (/ (count @items) page-size)))
              cp (or (some-> pag (deref) :current) 0)
              l (->> @items
                     (apply-sorting @sorting columns)
                     (drop (* cp page-size))
                     (take page-size))]
          (when (or (nil? @pag) (not= (:count pag) pc))
            (rf/dispatch [:pagination/set id {:count pc :current cp}]))
          [:<>
           [render-table (mark-sorting @sorting columns) l opts]
           (when (> pc 1)
             [render-pagination id pc cp])])))))

(defn add-sorting
  "Marks the column at given index with given sorting direction (`:asc` or `:desc`)"
  [cols idx dir]
  (mc/replace-nth idx
                  (-> (get cols idx)
                      (assoc :sorting dir))
                  cols)) 

