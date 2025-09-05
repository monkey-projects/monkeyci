(ns monkey.ci.e2e.common
  (:require [config.core :refer [env]]))

(defn sut-url
  "Constructs url for system under test with given path.  The url depends
   on env vars and app settings."
  [path]
  (str (get env :monkeyci-url "http://localhost:3000") path))
