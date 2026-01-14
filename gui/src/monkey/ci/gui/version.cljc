(ns monkey.ci.gui.version)

;; Frontend version
#?(:cljs (goog-define VERSION "develop")
   :clj (def VERSION "clj"))
