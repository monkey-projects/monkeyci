(ns monkey.ci.local.console
  "Event handlers for console reporting")

(def print-console
  "Interceptor that prints the configured messages to the console."
  {:name ::print-console
   :leave (fn [ctx]
            ;; TODO
            )})

