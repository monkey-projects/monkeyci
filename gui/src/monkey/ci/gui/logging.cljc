(ns monkey.ci.gui.logging
  (:require #?(:clj [clojure.tools.logging :as log])))

(def trace #?(:clj log/trace
              :cljs (.-trace js/console)))

(def debug #?(:clj log/debug
              :cljs (.-debug js/console)))

(def info #?(:clj log/info
             :cljs (.-info js/console)))

(def warn #?(:clj log/warn
              :cljs (.-warn js/console)))

(def error #?(:clj log/error
              :cljs (.-error js/console)))
