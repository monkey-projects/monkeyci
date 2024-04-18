(ns monkey.ci.extensions
  "Functionality for working with script extensions.  Extensions are a way
   for third party libraries to add functionality to scripts, that is easy
   to activate and can also be used in yaml-type scripts.  You could of 
   course also add regular functions to invoke, but this is not easy to
   use, especially when using container jobs.  Extensions do this by registering
   themselves under a specific namespaced keyword.  If this key is found in
   job properties, the associated extension code is executed.  Extensions can
   be executed before or after a job (or both).")

(defonce registered-extensions (atom {}))

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
