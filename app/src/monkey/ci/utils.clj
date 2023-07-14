(ns monkey.ci.utils)

(defn cwd
  "Returns current directory"
  []
  (System/getProperty "user.dir"))
