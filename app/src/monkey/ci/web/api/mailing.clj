(ns monkey.ci.web.api.mailing
  "Mailing api handlers"
  (:require [monkey.ci.storage :as st]
            [monkey.ci.web.common :as c]))

(c/make-entity-endpoints
 "mailing"
 {:get-id (comp :mailing-id :path :parameters)
  :getter st/find-mailing
  :saver st/save-mailing
  :deleter st/delete-mailing})
