(ns monkey.ci.gui.dagre
  "Graph visualization using Dagre-D3"
  (:require [reagent.core :as rc]
            ["react" :as react]
            ["dagre-d3" :as dagre]
            ["d3" :as d3]))

(defonce render (dagre/render))

(defn- make-graph []
  (dagre/dagre.graphlib.Graph.))

(defn- add-node [g n]
  (let [id (if (string? n) n (:id n))
        lbl (if (string? n) n (:label n))]
    (.setNode g id (clj->js (-> {:label lbl
                                 :rx 5
                                 :ry 5}
                                (merge (select-keys n [:style :labelStyle])))))))

(defn add-edge [g e]
  (.setEdge g (:from e) (:to e)
            (clj->js {})))

(defn- new-graph
  "Creates a new graphlib graph with the nodes and edges specified in the data"
  [data]
  (-> (make-graph)
      (.setGraph (clj->js {:rankdir "BT"}))
      (as-> g (reduce add-node
                      g (:nodes data)))
      (as-> g (reduce add-edge
                      g (:edges data)))))

(defn- render-network [el data]
  (let [sel (d3/select el)
        g (new-graph data)
        inner (.append sel "g")]
    ;; Render the graph to an inner g so we can translate and resize
    (render inner g)
    ;; Ensure a margin of 10 px
    (.attr inner "transform" "translate(10, 10)")
    (.attr sel "width" (+ (-> g (.graph) (.-width)) 20))
    (.attr sel "height" (+ (-> g (.graph) (.-height)) 20))))

(defn dagre-network [id data]
  (let [co-ref (react/createRef)]
    (rc/create-class
     {:display-name "dagre-network"
      :reagent-render
      (fn [config]
        [:svg {:id id :ref co-ref}])
      :component-did-mount
      (fn [_]
        (render-network (.-current co-ref) data))
      :component-did-update
      (fn [_ _]
        (render-network (.-current co-ref) data))})))

(def node-styles
  {:running "stroke: #334ac0;"
   :failure "stroke: #692340;"
   :skipped "stroke: #f1b980"})

(def label-styles
  {:running "stroke: #334ac0;"
   :failure "stroke: #692340;"
   :skipped "stroke: #f1b980"})

(defn jobs->network
  "Converts build jobs with their dependencies into a network with nodes and edges."
  [jobs]
  (let [n (map (fn [{:keys [id status]}]
                 (let [s (get node-styles status)
                       l (get label-styles status)]
                   (cond-> {:id id
                            :label id}
                     s (assoc :style s)
                     l (assoc :labelStyle l))))
               jobs)
        e (mapcat (fn [{:keys [id] :as job}]
                    (map (fn [d]
                           {:from id
                            :to d})
                         (:dependencies job)))
                  jobs)]
    {:nodes n
     :edges e}))
