(ns monkey.ci.credits
  "Credit calculation functions")

(defprotocol CreditConsumer
  "Jobs that consume credits implement this protocol"
  (credit-multiplier [job rt]
    "Calculates the credit multiplier for the given job.  This depends on the type
     of job, the type of container runner and the resources required by the job.
     This can be considered the 'credit consumption per minute' for the job."))

(def credit-consumer? (partial satisfies? CreditConsumer))

(defn calc-credit-multiplier [x rt]
  (if (credit-consumer? x)
    (credit-multiplier x rt)
    0))

(def default-consumer (constantly 0))

(defn runner-credit-consumer-fn
  "Returns the credit consumer function from the runtime associated with the
   build runner.  This is used to calculate the credit multiplier for an action 
   job, which is executed by the runner directly."
  [rt]
  (get-in rt [:runner :credit-consumer] default-consumer))

(defn container-credit-consumer-fn
  "Returns the credit consumer function associated with the container runner.
   This is used to calculate the credit multiplier for a container job.  If
   none is specified, it always returns zero."
  [rt]
  ;; TODO Use protocols instead
  (get-in rt [:containers :credit-consumer] default-consumer))
