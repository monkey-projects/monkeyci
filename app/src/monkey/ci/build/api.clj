(ns monkey.ci.build.api
  "Functions for invoking the script API."
  (:require [martian.core :as martian]))

(def ctx->api-client (comp :client :api))

;; Use memoize because we'll only want to fetch them once
(def ^:private fetch-params (memoize (comp deref #(martian/response-for % :get-params {}))))

(defn- error? [s]
  (>= s 400))

(defn- body-or-throw
  "If the response is successful, returns the body, otherwise throws an exception."
  [{:keys [status body] :as r}]
  (if (error? status)
    (throw (ex-info "Failed to invoke API call" r))
    body))

(defn build-params
  "Retrieves the parameters configured for this build. Returns a map
   of string/string."
  [ctx]
  (-> ctx
      ctx->api-client
      fetch-params
      body-or-throw))
