(ns monkey.ci.entities.mailing
  (:require [monkey.ci.entities.core :as ec]))

(defn select-sent-mailings [conn mailing-cuid]
  (->> {:select [:sm.*]
        :from [[:sent-mailings :sm]]
        :join [[:mailings :m] [:= :m.id :sm.mailing-id]]
        :where [:= :m.cuid mailing-cuid]}
       (ec/select conn)
       (map ec/convert-sent-mailing)))
