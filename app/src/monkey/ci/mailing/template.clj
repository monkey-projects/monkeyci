(ns monkey.ci.mailing.template
  "Mail template functionality, for replacing values."
  (:require [selmer.parser :as s]))

(defn apply-template
  "Applies the given string template using the `params`."
  [t params]
  (s/render t params))

