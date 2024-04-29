(ns monkey.ci.extensions
  "Functionality for working with script extensions.  Extensions are a way
   for third party libraries to add functionality to scripts, that is easy
   to activate and can also be used in yaml-type scripts.  You could of 
   course also add regular functions to invoke, but this is not easy to
   use, especially when using container jobs.  Extensions do this by registering
   themselves under a specific namespaced keyword.  If this key is found in
   job properties, the associated extension code is executed.  Extensions can
   be executed before or after a job (or both)."
  (:require [manifold.deferred :as md]
            [monkey.ci.jobs :as j]))

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

(defn- apply-extensions [{:keys [job] :as rt} registered rk mm]
  (->> (keys job)
       (reduce (fn [r k]
                 (let [b (or (get-in registered [k rk])
                             (find-mm k mm))]
                   (cond-> r
                     b (b))))
               rt)))

(defn apply-extensions-before
  ([rt registered]
   (apply-extensions rt registered :before before-job))
  ([rt]
   (apply-extensions-before rt @registered-extensions)))

(defn apply-extensions-after
  ([rt registered]
   (apply-extensions rt registered :after after-job))
  ([rt]
   (apply-extensions-after rt @registered-extensions)))

(defrecord ExtensionWrappingJob [target registered-ext]
  j/Job
  (execute! [job rt]
    (let [rt (assoc rt :job target)]
      ;; FIXME This is fairly dirty: jobs don't return the runtime, but extensions use it,
      ;; so maybe we need to think about reworking this.
      (-> rt
          (apply-extensions-before registered-ext)
          (as-> r (j/execute! target r))
          (md/chain
           ;; Add the result to the job in runtime
           (partial assoc-in rt [:job :result])
           ;; Let any extensions work on it
           #(apply-extensions-after % registered-ext)
           ;; Return the job result (possibly modified by extensions)
           (comp :result :job))))))

(defn wrap-job
  "Wraps job so that extensions are invoked before and after it."
  ([job registered-ext]
   (map->ExtensionWrappingJob (-> job
                                  (select-keys [j/job-id j/labels j/deps])
                                  (assoc :target job :registered-ext registered-ext))))
  ([job]
   (wrap-job job @registered-extensions)))

;;; Utility functions

(defn get-config
  "Retrieves configuration for the extension from the job"
  [rt k]
  (get-in rt [:job k]))

(defn set-value
  "Sets the extension value in the job.  This value will be stored with the job, so it
   must be serializable to `edn`."
  [rt k v]
  (assoc-in rt [:job :result k] v))
