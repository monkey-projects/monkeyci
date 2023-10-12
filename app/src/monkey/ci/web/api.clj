(ns monkey.ci.web.api
  (:require [clojure.tools.logging :as log]
            [monkey.ci.storage :as st]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(defn get-customer [req]
  (if-let [match (some-> (c/req->storage req)
                         (st/find-customer (get-in req [:parameters :path :customer-id])))]
    (rur/response match)
    (rur/not-found nil)))
