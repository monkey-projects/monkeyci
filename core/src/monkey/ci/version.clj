(ns monkey.ci.version)

;; Determine version at compile time
(defmacro version []
  (or (System/getenv "MONKEYCI_VERSION") "0.1.0-SNAPSHOT"))
