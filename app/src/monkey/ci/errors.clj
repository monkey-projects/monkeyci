(ns monkey.ci.errors
  "Functions for working with errors and exceptions.  This provides a uniform way
   to handle exceptions and convert them into a usable format for internal 
   propagation and to give useful feedback to the user.")

(def exception? (partial instance? java.lang.Exception))

(defrecord ApplicationError [type props])

(def make-error
  "Creates a new application error with type and properties"
  ->ApplicationError)

(def error-props
  "Retrieves error properties"
  :props)

(def error-type
  "Retrieves error type"
  :type)

(defn- get-prop [p]
  #(get-in % [:props p]))

(def error-msg (get-prop :message))
(def error-cause (get-prop :cause))

(defmulti ->error
  "Convert argument into an application error"
  class)

(defmethod ->error clojure.lang.ExceptionInfo [ex]
  (let [data (ex-data ex)]
    (make-error (:type data) (-> data
                                 (dissoc :type)
                                 (assoc :message (ex-message ex))))))

;; Error codes
(def error-script-failure 1)
(def error-process-failure 2)
(def error-no-script 3)
