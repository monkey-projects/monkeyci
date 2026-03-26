(ns monkey.ci.gui.test.scenes.dagre-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            #_[monkey.ci.gui.vis :as sut]
            [reagent.core :as rc]
            ["react" :as react]
            ["dagre-d3" :as dagre]
            ["d3" :as d3]))

(defonce render (dagre/render))

(defn- make-graph []
  (dagre/dagre.graphlib.Graph. (clj->js {:directed true})))

(defn- new-graph []
  (-> (make-graph)
      (.setGraph (clj->js {}))
      (.setDefaultEdgeLabel (constantly (clj->js {})))
      (.setNode "test-1" (clj->js {:label "<b>Test node</b>" :labelType "html" :style "fill: white"}))
      (.setNode "test-2" (clj->js {:label "Another node" :style "fill: red;"}))
      (.setNode "test-3" (clj->js {:label "Even more" :style "fill: white;"}))
      (.setEdge "test-1" "test-2" (clj->js {:label "dependency" :style "fill: red;"}))
      (.setEdge "test-3" "test-3")))

(defn- render-network [el & _]
  (let [sel (d3/select el)
        g (new-graph)]
    ;;(dagre/dagre.layout g)
    (.call sel render g)))

(defn dagre-network [data opts]
  (let [co-ref (react/createRef)]
    (rc/create-class
     {:display-name "dagre-network"
      :reagent-render
      (fn [config]
        [:svg {:ref co-ref :style {:height "300px"}} [:g]])
      :component-did-mount
      (fn [_]
        (render-network (.-current co-ref) data opts))
      :component-did-update
      (fn [_ _]
        (render-network (.-current co-ref) data opts))})))

(defscene basic-network
  [dagre-network {} {}])
