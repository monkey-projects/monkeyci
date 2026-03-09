(ns predicates
  "Common predicates, used to determine if certain jobs should run or not."
  (:require [monkey.ci.api :as m]))

(def tag-regex #"^refs/tags/(\d+\.\d+\.\d+(\.\d+)?$)")

(defn ref?
  "Returns a predicate that checks if the ref matches the given regex"
  [re]
  #(m/ref-regex % re))

(def release?
  (ref? tag-regex))

(def api-trigger?
  (comp (partial = :api)
        m/source))

(def should-publish?
  (some-fn m/main-branch? release?))

(defn- dir-changed?
  "True if files have been touched for the given regex, or the 
   build was triggered from the api."
  [ctx re]
  (or (m/touched? ctx re)      
      (api-trigger? ctx)))

(defn app-changed? [ctx]
  (dir-changed? ctx #"^app/.*"))

(defn gui-changed? [ctx]
  (dir-changed? ctx #"^gui/.*"))

(defn test-lib-changed? [ctx]
  (dir-changed? ctx #"^test-lib/.*"))

(defn common-changed? [ctx]
  (dir-changed? ctx #"^common/.*"))

(def build-app? (some-fn app-changed? release? m/manual?))
(def build-gui? (some-fn gui-changed? release? m/manual?))
(def build-test-lib? (some-fn test-lib-changed? release? m/manual?))
(def build-common? (some-fn common-changed? release? m/manual?))

(def publish-app? (some-fn (every-pred app-changed? should-publish?)
                           release?))
(def publish-gui? (some-fn (every-pred gui-changed? should-publish?)
                           release?))
(def publish-test-lib? (some-fn (every-pred test-lib-changed? should-publish?) release?))
(def publish-common? (some-fn (every-pred common-changed? should-publish?) release?))
