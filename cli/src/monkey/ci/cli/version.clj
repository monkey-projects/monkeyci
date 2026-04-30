(ns monkey.ci.cli.version)

(def version (or (System/getenv "MONKEYCI_VERSION") "dev"))

