(ns monkey.ci.test.runtime.sidecar
  "Helper functions for working with sidecar runtimes"
  (:require [monkey.ci.logging :as l]
            [monkey.ci.test.mailman :as tm]))

(defn set-log-maker [rt e]
  (assoc rt :log-maker e))

(defn set-events-file [rt f]
  (assoc-in rt [:paths :events-file] f))

(defn set-start-file [rt f]
  (assoc-in rt [:paths :start-file] f))

(defn set-abort-file [rt f]
  (assoc-in rt [:paths :abort-file] f))

(defn set-poll-interval [rt i]
  (assoc rt :poll-interval i))

(defn set-build [rt b]
  (assoc rt :build b))

(defn set-job [rt job]
  (assoc rt :job job))

(defn set-mailman [rt mm]
  (assoc rt :mailman mm))

(def test-rt {})

(defn make-test-rt [& [conf]]
  (-> test-rt
      (set-log-maker (constantly (l/->InheritLogger)))
      (set-events-file "test-events")
      (set-start-file "test-start")
      (set-abort-file "test-abort")
      (set-poll-interval 100)
      (set-build {:build-id (str "test-build-" (random-uuid))
                  :workspace "test-ws"})
      (set-job {:id (str "test-job-" (random-uuid))})
      (set-mailman (tm/test-component))
      (merge conf)))
