(ns user
  (:require [clojure.tools.namespace.repl :as nr]
            [kaocha.repl :as k]))

(defn refresh []
  (nr/refresh))

(defn run-all-tests []
  (k/run :unit))
