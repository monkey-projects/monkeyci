(ns monkey.ci.gui.edn
  (:require [clojure.tools.reader.edn :as edn]))

(def custom-readers {'regex re-pattern})

(defn read-string [edn]
  (edn/read-string {:readers custom-readers} edn))

;; For cljs.reader/read-string
(doseq [[s f] custom-readers]
  (cljs.reader/register-tag-parser! s f))
