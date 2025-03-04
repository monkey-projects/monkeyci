(ns monkey.ci.test.config
  "Helper functions for app configs")

(def base-config
  {:events      {:type :manifold}
   :artifacts   {:type :disk
                 :dir  "/tmp"}
   :cache       {:type :disk
                 :dir  "/tmp"}
   :build-cache {:type :disk
                 :dir  "/tmp"}
   :workspace   {:type :disk
                 :dir  "/tmp"}
   :containers  {:type :oci}
   :storage     {:type :memory}
   :runner      {:type :child}
   :mailman     {:type :manifold}})

(def app-config
  (assoc base-config :vault {:type :noop}))
