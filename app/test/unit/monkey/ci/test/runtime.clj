(ns monkey.ci.test.runtime
  "Helper functions for working with runtime in tests"
  (:require [monkey.ci.helpers :as h]))

(def empty-runtime {})

(defn set-artifacts [rt a]
  (assoc rt :artifacts a))

(defn set-cache [rt c]
  (assoc rt :cache c))

(defn set-workspace [rt w]
  (assoc rt :workspace w))

(defn set-events [rt e]
  (assoc rt :events e))

(defn test-runtime []
  (-> empty-runtime
      (set-artifacts (h/fake-blob-store))
      (set-cache (h/fake-blob-store))
      (set-workspace (h/fake-blob-store))
      (set-events (h/fake-events))))
