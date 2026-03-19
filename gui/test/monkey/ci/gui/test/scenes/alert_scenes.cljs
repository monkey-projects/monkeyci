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

(defscene warning
  [c/render-alert
   {:type :warning
    :message "Have you ever heard about global warning?"}])

(defscene info
  [c/render-alert
   {:type :info
    :message "Just telling you something useful."}])
