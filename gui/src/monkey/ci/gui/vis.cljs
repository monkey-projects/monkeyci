(ns monkey.ci.gui.vis
  "Network visualization components using vis"
  (:require [reagent.core :as rc]
            ["react" :as react]
            ["vis-network" :as vis]))

(def default-opts
  {:nodes
   {:shape "box"
    :widthConstraint {:minimum 80}
    :heightConstraint {:minimum 30}
    :font
    {:face "Roboto, sans-serif"}
    :color
    {:background "white"
     :border "#008060"}
    :shadow
    {:enabled true
     :size 3
     :color "#d0d0d0"}}
   :edges
   {:arrows
    {:to {:enabled true}}}
   :layout
   {:hierarchical
    {:enabled false}
    #_{:direction "DU"
       :sortMethod "directed"}}
   :physics
   {:solver "repulsion"}})

(defn- render-network [el data opts]
  (vis/Network. el
                (clj->js data)
                (clj->js (merge default-opts opts))))

(defn vis-network [id data opts]
  (let [co-ref (react/createRef)]
    (rc/create-class
     {:display-name "vis-network"
      :reagent-render
      (fn [config]
        [:div.w-100.h-100 {:id (str id) :ref co-ref}])
      :component-did-mount
      (fn [_]
        (render-network (.-current co-ref) data opts))
      :component-did-update
      (fn [_ _]
        (render-network (.-current co-ref) data opts))})))

(def node-colors
  {:success "#15837e"
   :failure "#692340"
   :running "#334ac0"
   :skipped "#f1b980"})

(def text-colors
  {:success "#15837e"
   :failure "#692340"
   :running "#334ac0"})

(defn jobs->network
  "Converts build jobs with their dependencies into a network with nodes and edges."
  [jobs]
  (let [n (map (fn [{:keys [id status]}]
                 (let [c (get node-colors status)
                       f (get text-colors status)]
                   (cond-> {:id id
                            :label id}
                     c (assoc-in [:color :border] c)
                     f (assoc-in [:font :color] f))))
               jobs)
        e (mapcat (fn [{:keys [id] :as job}]
                    (map (fn [d]
                           {:from id
                            :to d})
                         (:dependencies job)))
                  jobs)]
    {:nodes n
     :edges e}))
