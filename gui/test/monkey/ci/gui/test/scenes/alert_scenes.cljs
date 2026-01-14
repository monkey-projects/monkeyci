(ns monkey.ci.gui.test.scenes.alert-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.alerts :as sut]
            [monkey.ci.gui.components :as c]))

(defscene github-error
  :params {:msg "no permission"}
  [params _]
  [c/render-alert
   (sut/org-github-repos-failed
    (:msg params))])
