(ns monkey.ci.extensions
  "Functionality for working with script extensions.  Extensions are a way
   for third party libraries to add functionality to scripts, that is easy
   to activate and can also be used in yaml-type scripts.  You could of 
   course also add regular functions to invoke, but this is not easy to
   use, especially when using container jobs.  Extensions do this by registering
   themselves under a specific namespaced keyword.  If this key is found in
   job properties, the associated extension code is executed.  Extensions can
   be executed before or after a job (or both)."
  (:require [clojure.tools.logging :as log]
            [monkey.ci.events.mailman.interceptors :as emi]))

(def new-register {})

(defonce registered-extensions (atom new-register))

(defn register [l ext]
  (assoc l (:key ext) ext))

(defn register! [ext]
  (swap! registered-extensions register ext))

(defmulti before-job (fn [k _] k))

(defmulti after-job (fn [k _] k))

(defn- find-mm
  "Finds multimethod for `mm` and given key, returns a fn that invokes it with runtime arg."
  [k mm]
  (when-let [m (get-method mm k)]
    #(m k %)))

(defn- run-safe [f ctx]
  (try
    (f ctx)
    (catch Exception ex
      (log/error "Failed to apply extension" ex)
      ;; Return context unchanged
      ctx)))

(defn- apply-extensions [{:keys [job] :as ctx} registered rk mm]
  (->> (keys job)
       (reduce (fn [r k]
                 (let [b (or (get-in registered [k rk])
                             (find-mm k mm))]
                   (cond->> r
                     b (run-safe b))))
               ctx)))

(defn apply-extensions-before
  ([ctx registered]
   (apply-extensions ctx registered :before before-job))
  ([ctx]
   (apply-extensions-before ctx @registered-extensions)))

(defn apply-extensions-after
  ([ctx registered]
   (apply-extensions ctx registered :after after-job))
  ([ctx]
   (apply-extensions-after ctx @registered-extensions)))

;;; Utility functions

(defn get-config
  "Retrieves configuration for the extension from the job"
  [ctx k]
  (get-in ctx [:job k]))

(defn update-job
  "Applies `f` with args to the job in the given job context.  This is useful for before
   interceptors to modify the job that will be executed."
  [ctx f & args]
  (apply update ctx :job f args))

(defn set-value
  "Sets the extension value in the job.  This value will be stored with the job, so it
   must be serializable to `edn`."
  [ctx k v]
  (update-job ctx assoc-in [:result k] v))

;;; Interceptors

(def before-interceptor
  "Interceptor that applies the `before` extensions to the job in the job context.
   This expects the job context to be present in the event context, with the job
   added to that context."
  {:name ::before
   :enter (fn [ctx]
            (emi/update-job-ctx ctx apply-extensions-before))})

(def after-interceptor
  "Interceptor that applies the `after` extensions to the job in the job context.
   This expects the job context to be present in the event context, with the job
   and it's execution result added to that context."
  {:name ::after
   :enter (fn [ctx]
            (emi/update-job-ctx ctx apply-extensions-after))})
