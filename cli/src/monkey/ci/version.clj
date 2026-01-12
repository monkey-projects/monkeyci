(ns monkey.ci.version)

(defmacro version []
  (or (System/getenv "MONKEYCI_VERSION") "dev"))
