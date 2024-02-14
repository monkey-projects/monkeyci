(ns monkey.ci.context
  "Helper functions for working with the context.  The context is created from the configuration
   and possible command-line arguments, and can hold non-serializable things as well, like
   functions.  It is used by the application to execute functionality.

   This is being replaced by the runtime."
  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci
             [blob :as b]
             [config :as c]
             [events :as legacy-events]
             [logging :as l]
             [utils :as u]]
            [monkey.ci.events.core :as ec]))

(defn post-events
  "Posts one or more events using the event poster in the context"
  [{:keys [event-bus event-poster]} evt]
  (letfn [(post-legacy [evt]
            (if (sequential? evt)
              (doseq [e evt]
                (legacy-events/post-event event-bus e))
              (legacy-events/post-event event-bus evt)))]
    (cond
      (some? event-poster) (event-poster evt)
      ;; Backwards compatibility
      (some? event-bus) (post-legacy evt)
      :else (log/warn "Unable to post events, no event poster or bus in context"))))

(defn- build-related-dir
  ([base-dir-key ctx build-id]
   (some-> ctx
           (base-dir-key)
           (u/combine build-id)))
  ([base-dir-key ctx]
   (build-related-dir ctx base-dir-key (get-in ctx [:build :build-id]))))

(def checkout-dir
  "Calculates the checkout directory for the build, by combining the checkout
   base directory and the build id."
  (partial build-related-dir :checkout-base-dir))

(def ssh-keys-dir
  "Calculates ssh keys dir for the build"
  (partial build-related-dir :ssh-keys-dir))

(defn ^:deprecated log-dir
  "Gets the directory where to store log files"
  [ctx]
  (or (some-> (get-in ctx [:logging :dir]) (u/abs-path))
      (u/combine (u/tmp-dir) "logs")))

(defn log-maker [ctx]
  (or (get-in ctx [:logging :maker])
      (l/make-logger {})))

(defn log-retriever [ctx]
  (get-in ctx [:logging :retriever]))

(def step-work-dir
  "Given a context, determines the step working directory.  This is either the
   work dir as configured on the step, or the context work dir, or the process dir."
  (comp
   (memfn getCanonicalPath)
   io/file
   (some-fn (comp :work-dir :step)
            :checkout-dir
            (constantly (u/cwd)))))

(defn step-relative-dir
  "Calculates path `p` as relative to the work dir for the current step"
  [ctx p]
  (u/abs-path (step-work-dir ctx) p))

(defn ctx->env
  "Build the environment from the context to be passed to an external process."
  [ctx]
  (-> ctx
      (select-keys [:oci :containers :log-dir :build :cache :artifacts])
      (mc/update-existing-in [:build :sid] u/serialize-sid)
      (assoc :logging (dissoc (:logging ctx) :maker))
      (update :cache dissoc :store)))

(def account->sid (juxt :customer-id :repo-id))

(defn get-sid
  "Gets current build sid from the context.  This is either specified directly,
   or taken from account settings."
  [ctx]
  (or (get-in ctx [:build :sid])
      (let [sid (->> (account->sid (:account ctx))
                     (take-while some?))]
        (when (= 2 (count sid))
          sid))))

(defn get-build-id [ctx]
  (or (get-in ctx [:build :build-id]) "unknown-build"))

(def get-step-sid
  "Creates a unique step id using the build id, pipeline and step from the context."
  (comp (partial mapv str)
        (juxt get-build-id
              (comp (some-fn :name :index) :pipeline)
              (comp :index :step))))

(def get-step-id
  "Creates a string representation of the step sid"
  (comp (partial cs/join "-") get-step-sid))

(def reporter :reporter)

(defn report
  "Reports `obj` to the user with the reporter from the context."
  [ctx obj]
  (when-let [r (reporter ctx)]
    (r obj)))

(defn- configure-blob [k ctx]
  (mc/update-existing ctx k (fn [c]
                              (when (some? (:type c))
                                (assoc c :store (b/make-blob-store ctx k))))))

(def configure-workspace (partial configure-blob :workspace))
(def configure-cache     (partial configure-blob :cache))
(def configure-artifacts (partial configure-blob :artifacts))

(def default-script-config
  "Default configuration for the script runner."
  {:containers {:type :docker}
   :storage {:type :memory}
   :logging {:type :inherit}})

(defn initialize-log-maker [conf]
  (assoc-in conf [:logging :maker] (l/make-logger conf)))

(defn initialize-log-retriever [conf]
  (assoc-in conf [:logging :retriever] (l/make-log-retriever conf)))

(defn initialize-events [conf]
  (let [evt (when (:events conf) (ec/make-events conf))]
    (cond-> conf
      evt (assoc :event-poster (partial ec/post-events evt)))))

(defn script-context
  "Builds context used by the child script process"
  [env args]
  (-> default-script-config
      (c/normalize-config (c/strip-env-prefix env) args)
      (merge args)
      (initialize-log-maker)
      (configure-cache)
      (configure-artifacts)))
