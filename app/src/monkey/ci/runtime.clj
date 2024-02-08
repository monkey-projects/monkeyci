(ns monkey.ci.runtime
  "The runtime can be considered the 'live configuration'.  It is created
   from the configuration, and is passed on to the application modules.  The
   runtime provides the information (often in the form of functions) needed
   by the modules to perform work.  This allows us to change application 
   behaviour depending on configuration, but also when testing."
  (:require [clojure.spec.alpha :as spec]
            [monkey.ci.spec :as s]))

(def default-runtime
  {:http {:port 3000}
   :runner (constantly 1)
   :git
   {:fn (constantly nil)}
   :storage
   {}
   :logging
   {:maker (constantly nil)
    :retriever nil}
   :public-api (constantly nil)})

(defmulti setup-runtime (fn [_ k] k))

(defmethod setup-runtime :default [_ k]
  (get default-runtime k))

(defn config->runtime
  "Creates the runtime from the normalized config map"
  [conf]
  {:pre  [(spec/valid? ::s/app-config conf)]
   ;;:post [(spec/valid? ::s/app-context %)]
   }
  (reduce-kv (fn [r k v]
               (assoc r k (setup-runtime conf k)))
             default-runtime
             conf))
