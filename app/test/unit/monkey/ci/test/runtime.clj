(ns monkey.ci.test.runtime
  "Helper functions for working with runtime in tests"
  (:require
   [manifold.deferred :as md]
   [monkey.ci.storage :as s]
   [monkey.ci.test.helpers :as h]
   [monkey.ci.test.mailman :as tmm]
   [monkey.ci.web.auth :as auth]))

(def empty-runtime {})

(defn set-artifacts [rt a]
  (assoc rt :artifacts a))

(defn set-cache [rt c]
  (assoc rt :cache c))

(defn set-build-cache [rt c]
  (assoc rt :build-cache c))

(defn set-workspace [rt w]
  (assoc rt :workspace w))

(defn set-mailman [rt mm]
  (assoc rt :mailman {:broker mm}))

(def get-mailman (comp :broker :mailman))

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

(defn set-process-reaper [rt pr]
  (assoc rt :process-reaper pr))

(defn set-vault [rt v]
  (assoc rt :vault v))

(defn test-runtime []
  (-> empty-runtime
      (set-artifacts (h/fake-blob-store))
      (set-cache (h/fake-blob-store))
      (set-build-cache (h/fake-blob-store))
      (set-workspace (h/fake-blob-store))
      (set-mailman (tmm/test-broker))
      (set-storage (s/make-memory-storage))
      (set-jwk (auth/keypair->rt (auth/generate-keypair)))
      (set-containers (h/fake-container-runner))
      (set-runner (constantly (md/success-deferred 0)))
      (set-process-reaper (constantly []))
      (set-vault (h/fake-vault))))
