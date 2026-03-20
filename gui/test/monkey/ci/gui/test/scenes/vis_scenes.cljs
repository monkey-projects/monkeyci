(ns monkey.ci.gui.test.scenes.vis-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.vis :as sut]))

(defscene basic-network
  [sut/vis-network
   ::basic
   {:nodes [{:id 1 :label "first"}
            {:id 2 :label "second"}]
    :edges [{:from 1 :to 2}]}])

(defscene extended-network
  [:div {:style {:height "500px"}}
   [sut/vis-network
    ::extended
    {:nodes [{:id 1 :label "compile"}
             {:id 2 :label "unit-tests"
              :chosen {:node (fn [_ _ sel _]
                               (when sel
                                 (js/alert "You chose wisely")))}}
             {:id 3 :label "jar"}
             {:id 4 :label "publish" :color {:background "#ff0000"}}
             {:id 5 :label "notify"}
             {:id 6 :label "validate"}]
     :edges [{:from 2 :to 1}
             {:from 3 :to 1}
             {:from 4 :to 3}
             {:from 5 :to 4}
             {:from 5 :to 3}]}]])

(defscene jobs-network
  [:div {:style {:height "500px"}}
   [sut/vis-network
    ::jobs
    (sut/jobs->network
     [{:id "unit-tests"
       :status :success}
      {:id "publish"
       :dependencies ["unit-tests"]
       :status :failure}
      {:id "uberjar"
       :dependencies ["unit-tests"]
       :status :running}
      {:id "image"
       :dependencies ["uberjar"]}])
    {:layout
     {:hierarchical
      {:direction "DU"
       :sortMethod "directed"
       :shakeTowards "leaves"}}}]])
