(ns monkey.ci.context
  "Helper functions for working with the context.  The context is created from the configuration
   and possible command-line arguments, and can hold non-serializable things as well, like
   functions.  It is used by the application to execute functionality."
  (:require [clojure.java.io :as io]
            [medley.core :as mc]
            [monkey.ci
             [logging :as l]
             [utils :as u]]))

(defn checkout-dir
  "Calculates the checkout directory for the build, by combining the checkout
   base directory and the build id."
  ([ctx build-id]
   (some-> ctx
           :checkout-base-dir
           (u/combine build-id)))
  ([ctx]
   (checkout-dir ctx (get-in ctx [:build :build-id]))))

(defn ^:deprecated log-dir
  "Gets the directory where to store log files"
  [ctx]
  (or (some-> (get-in ctx [:logging :dir]) (u/abs-path))
      (u/combine (u/tmp-dir) "logs")))

(defn log-maker [ctx]
  (or (get-in ctx [:logging :maker])
      (l/make-logger {})))

(def step-work-dir
  "Given a context, determines the step working directory.  This is either the
   work dir as configured on the step, or the context work dir, or the process dir."
  (comp
   (memfn getCanonicalPath)
   io/file
   (some-fn (comp :work-dir :step)
            :checkout-dir
            (constantly (u/cwd)))))

(defn ctx->env
  "Build the environment from the context to be passed to an external process."
  [ctx]
  (-> ctx
      (select-keys [:oci :containers :log-dir :build])
      (mc/update-existing-in [:build :sid] u/serialize-sid)
      (assoc :logging (dissoc (:logging ctx) :maker))))

(def reporter :reporter)

(defn report
  "Reports `obj` to the user with the reporter from the context."
  [ctx obj]
  (when-let [r (reporter ctx)]
    (r obj)))
