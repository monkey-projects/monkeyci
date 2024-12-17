(ns monkey.ci.gui.logging
  (:require #?(:clj [clojure.tools.logging :as log])))

#?(:clj
   (defn log-fn [level msg]
     (log/log level msg)))

(def trace #?@(:clj  [(partial log-fn :trace)]
               :node [(constantly nil)]
               :cljs [(.-trace js/console)]))

(def debug #?@(:clj  [(partial log-fn :debug)]
               :node [(constantly nil)]
               :cljs [(.-debug js/console)]))

(def info  #?@(:clj  [(partial log-fn :info)]
               :node [(constantly nil)]
               :cljs [(.-info js/console)]))

(def warn  #?@(:clj  [(partial log-fn :warn)]
               :node [(constantly nil)]
               :cljs [(.-warn js/console)]))

(def error #?@(:clj  [(partial log-fn :error)]
               :node [(constantly nil)]
               :cljs [(.-error js/console)]))
