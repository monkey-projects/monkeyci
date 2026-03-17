(ns monkey.ci.gui.dashboard.events
  (:require [monkey.ci.gui.dashboard.db :as db]
            [re-frame.core :as rf]))

(rf/reg-event-db
 ::initialize-db
 (fn [db _]
   (-> db
       (db/set-assets-url "http://localhost:8083/assets/img/")
       (db/set-metrics :total-runs {:curr-value 128
                                    :last-value 100
                                    :avg-value 150
                                    :status :success})
       (db/set-active-builds
        [{:repo "monkeyci"
          :git-ref "main"
          :build-idx 1256
          :trigger-type :push
          :elapsed "2m 14s"
          :status :running
          :progress 0.6}
         {:repo "infra"
          :git-ref "main"
          :build-idx 454
          :trigger-type :push
          :elapsed "4m 06s"
          :status :running
          :progress 0.3}
         {:repo "website"
          :git-ref "main"
          :build-idx 230
          :trigger-type :push
          :elapsed "1m 15s"
          :status :error
          :progress 0.6}
         {:repo "monkeyci"
          :git-ref "main"
          :build-idx 1255
          :trigger-type :api
          :elapsed "3m 45s"
          :status :success
          :progress 1}
         {:repo "infra"
          :git-ref "terraform"
          :build-idx 455
          :trigger-type :push
          :status :queued}
         {:repo "infra"
          :git-ref "terraform"
          :build-idx 453
          :trigger-type :push
          :status :success}]))))

(rf/reg-event-fx
 ::list-orgs
 (fn [_ _]
   ))
