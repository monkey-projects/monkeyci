(ns monkey.ci.script.interceptors
  "Babashka-compatible interceptor executor")

(defn- apply-enter [{ex ::exception :as ctx} {:keys [enter]}]
  (if ex
    ;; If there was an error, ignore other `enter` stages
    ctx
    (try 
      (cond-> ctx
        enter (enter))
      (catch Exception ex
        (assoc ctx ::exception ex)))))

(defn- apply-leave [{ex ::exception :as ctx} {:keys [leave error]}]
  (if ex
    (if error
      ;; Should handle error
      (error (dissoc ctx ::exception) ex)
      ctx)
    (cond-> ctx
      leave (leave))))

(defn- maybe-rethrow [{ex ::exception :as ctx}]
  (when ex
    (throw ex))
  ctx)

(defn execute [chain ctx]
  (-> (reduce apply-enter ctx chain)
      (as-> c (reduce apply-leave c (reverse chain)))
      (maybe-rethrow)))
