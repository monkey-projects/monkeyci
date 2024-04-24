(ns monkey.ci.gui.job.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.job.events :as e]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(defn page []
  (rf/dispatch [:job/init])
  (let [p (rf/subscribe [:route/current])
        job-id (-> @p
                   (r/path-params)
                   :job-id)]
    [l/default
     [:h2 "Logs for job: " job-id]
     [co/alerts e/alerts]]))
