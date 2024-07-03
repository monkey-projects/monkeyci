(ns monkey.ci.credits
  "Credit calculation functions")

(defprotocol CreditConsumer
  "Jobs that consume credits implement this protocol"
  (credit-multiplier [job rt]
    "Calculates the credit multiplier for the given job.  This depends on the type
     of job, the type of container runner and the resources required by the job.
     This can be considered the 'credit consumption per minute' for the job."))

(defn runner-credit-consumer-fn
  "Returns the credit consumer function from the runtime associated with the
   build runner.  This is used to calculate the credit multiplier for an action 
   job, which is executed by the runner directly."
  [rt]
  (get-in rt [:runner :credit-consumer] (constantly 0)))

(defn container-credit-consumer-fn
  "Returns the credit consumer function associated with the container runner.
   This is used to calculate the credit multiplier for a container job.  If
   none is specified, it always returns zero."
  [rt]
  (get-in rt [:containers :credit-consumer] (constantly 0)))
