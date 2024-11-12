(ns monkey.ci.test.runtime
  "Helper functions for working with runtime in tests"
  (:require [manifold.deferred :as md]
            [monkey.ci.helpers :as h]
            [monkey.ci.storage :as s]
            [monkey.ci.web.auth :as auth]))

(def empty-runtime {})

(defn set-artifacts [rt a]
  (assoc rt :artifacts a))

(defn set-cache [rt c]
  (assoc rt :cache c))

(defn set-workspace [rt w]
  (assoc rt :workspace w))

(defn set-events [rt e]
  (assoc rt :events e))

(defn set-config [rt c]
  (assoc rt :config c))

(defn set-storage [rt s]
  (assoc rt :storage s))

(defn set-jwk [rt k]
  (assoc rt :jwk k))

(defn set-containers [rt c]
  (assoc rt :containers c))

(defn set-runner [rt r]
  (assoc rt :runner r))

(defn test-runtime []
  (-> empty-runtime
      (set-artifacts (h/fake-blob-store))
      (set-cache (h/fake-blob-store))
      (set-workspace (h/fake-blob-store))
      (set-events (h/fake-events))
      (set-storage (s/make-memory-storage))
      (set-jwk (auth/keypair->rt (auth/generate-keypair)))
      (set-containers (h/fake-container-runner))
      (set-runner (constantly (md/success-deferred 0)))))
