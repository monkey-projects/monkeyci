(ns monkey.ci.test.config
  "Helper functions for app configs")

(def base-config
  {:events     {:type :manifold}
   :artifacts  {:type :disk
                :dir  "/tmp"}
   :cache      {:type :disk
                :dir  "/tmp"}
   :workspace  {:type :disk
                :dir  "/tmp"}
   :containers {:type :podman}
   :storage    {:type :memory}
   :runner     {:type :child}})
