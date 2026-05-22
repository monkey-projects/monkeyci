(ns monkey.ci.cli.version)

(defmacro version []
  (or (System/getenv "MONKEYCI_VERSION") "dev"))
