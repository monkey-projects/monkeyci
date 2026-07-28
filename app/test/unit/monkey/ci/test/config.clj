(ns monkey.ci.test.config
  "Helper functions for app configs")

(def base-config
  {:artifacts   {:type :disk
                 :dir  "/tmp"}
   :cache       {:type :disk
                 :dir  "/tmp"}
   :build-cache {:type :disk
                 :dir  "/tmp"}
   :workspace   {:type :disk
                 :dir  "/tmp"}
   :containers  {:type :agent}
   :storage     {:type :memory}
   :runner      {:type :child}
   :mailman     {:type :manifold}})

(def app-config base-config)
