(ns monkey.ci.web.script-api
  "API functionality that can be accessed by the build script.  This can either
   be part of the public API, or exposed through a UDS from the build runner.  The
   script API does not have security on its own, since the implementation will
   take care of this.  The script API exposes an OpenAPI spec, which is then
   fetched by the script, so it knows which services are available."
  (:require [monkey.ci.web.common :as c]
            [reitit.ring :as ring]))

(def routes [])

(defn make-router
  ([opts routes]
   (ring/router
    routes
    {:data {:middleware c/default-middleware
            :muuntaja (c/make-muuntaja)
            :coercion reitit.coercion.schema/coercion
            ::context opts}}))
  ([opts]
   (make-router opts routes)))
