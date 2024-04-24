(ns monkey.ci.gui.logging
  (:require #?(:clj [clojure.tools.logging :as log])))

(def trace #?@(:clj  [log/trace]
               :node [(constantly nil)]
               :cljs [(.-trace js/console)]))

(def debug #?@(:clj  [log/debug]
               :node [(constantly nil)]
               :cljs [(.-debug js/console)]))

(def info  #?@(:clj  [log/info]
               :node [(constantly nil)]
               :cljs [(.-info js/console)]))

(def warn  #?@(:clj  [log/warn]
               :node [(constantly nil)]
               :cljs [(.-warn js/console)]))

(def error #?@(:clj  [log/error]
               :node [(constantly nil)]
               :cljs [(.-error js/console)]))
