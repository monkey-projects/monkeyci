(ns monkey.ci.context
  "Helper functions for working with the context.  The context is created from the configuration
   and possible command-line arguments, and can hold non-serializable things as well, like
   functions.  It is used by the application to execute functionality."
  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [medley.core :as mc]
            [monkey.ci
             [logging :as l]
             [utils :as u]]))

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

(def account->sid (juxt :customer-id :project-id :repo-id))

(defn get-sid
  "Gets current build sid from the context.  This is either specified directly,
   or taken from account settings."
  [ctx]
  (or (get-in ctx [:build :sid])
      (let [sid (->> (account->sid (:account ctx))
                     (take-while some?))]
        (when (= 3 (count sid))
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
