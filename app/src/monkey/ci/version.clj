(ns monkey.ci.version)

(def ^:dynamic *version* nil)

;; Determine version at compile time
(defmacro version []
  (or (System/getenv "MONKEYCI_VERSION") "0.1.0-SNAPSHOT"))

(defmacro with-version [v & body]
  `(binding [*version* ~v]
     ~@body))
