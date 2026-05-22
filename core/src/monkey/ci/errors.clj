(ns monkey.ci.errors)

;; Error codes
(def error-script-failure 1)
(def error-process-failure 2)
(def error-no-script 3)
(def error-script-timeout 4)

(defn ->str [x]
  (if (instance? java.io.InputStream x)
    (slurp x)
    (str x)))

(defn unwrap-exception
  "Unwraps the exception to its cause, if any.  If the exception contains a body, 
   returns a new exception with the body converted into  a string.  This is useful
   when logging HTTP client errors."
  [ex]
  (let [ex (or (ex-cause ex) ex)
        data (ex-data ex)]
    ;; If it's a http error, there may be a body that can be read
    (if (some? (:body data))
      (ex-info (->str (:body data))
               data)
      ex)))
