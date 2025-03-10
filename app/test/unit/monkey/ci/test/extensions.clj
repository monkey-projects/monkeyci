(ns monkey.ci.test.extensions
  (:require [monkey.ci.extensions :as ext]))

(defmacro with-extensions [& body]
  `(let [ext# @ext/registered-extensions]
     (try
       (reset! ext/registered-extensions ext/new-register)
       ~@body
       (finally
         (reset! ext/registered-extensions ext#)))))
