(ns monkey.ci.script.build
  "Build utility functions")

(def success
  {:status :success})

(def script "Gets script from the build"
  :script)

(def script-dir
  "Gets script dir from the build"
  (comp :script-dir script))

(defn set-script-dir [b d]
  (assoc-in b [:script :script-dir] d))
