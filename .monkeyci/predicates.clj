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

(defn cli-changed? [ctx]
  (dir-changed? ctx #"^cli/.*"))

(defn script-changed? [ctx]
  (dir-changed? ctx #"^script/.*"))

(defn core-changed? [ctx]
  (dir-changed? ctx #"^core/.*"))

(def build-app? (some-fn app-changed? release? m/manual?))
(def build-gui? (some-fn gui-changed? release? m/manual?))
(def build-test-lib? (some-fn test-lib-changed? release? m/manual?))
(def build-common? (some-fn common-changed? release? m/manual?))
(def build-cli? (some-fn cli-changed? release? m/manual?))
(def build-core? (some-fn core-changed? release? m/manual?))
(def build-script? (some-fn script-changed? release? m/manual?))

(defn- publish-or-changed? [p]
  (some-fn (every-pred p should-publish?) release?))

(def publish-app? (publish-or-changed? app-changed?))
(def publish-gui? (publish-or-changed? gui-changed?))
(def publish-test-lib? (publish-or-changed? test-lib-changed?))
(def publish-common? (publish-or-changed? common-changed?))
(def publish-core? (publish-or-changed? core-changed?))
(def publish-script? (publish-or-changed? script-changed?))
(def publish-cli? (publish-or-changed? cli-changed?))
