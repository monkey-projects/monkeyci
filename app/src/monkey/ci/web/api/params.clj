(ns monkey.ci.web.api.params
  "Api functions for managing build parameters"
  (:require [monkey.ci.storage :as st]
            [monkey.ci.web.common :as c]))

(def get-customer-params
  "Retrieves all parameters configured on the customer.  This is for administration purposes."
  (partial c/get-list-for-customer (comp c/drop-ids st/find-params)))

(c/make-entity-endpoints
 "param"
 {:get-id (fn [req]
            (st/params-sid (c/customer-id req)
                           (get-in req [:parameters :path :param-id])))
  :getter st/find-param
  :saver st/save-param
  :deleter st/delete-param})

(def get-repo-params
  "Retrieves the parameters that are available for the given repository.  This depends
   on the parameter label filters and the repository labels."
  (partial c/get-for-repo-by-label st/find-params (mapcat :parameters)))

(def update-params
  (partial c/update-for-customer st/save-params))
