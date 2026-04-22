(ns monkey.ci.common.constants)

(def max-script-timeout
  "Max msecs a build script can run before we terminate it"
  ;; One hour
  (* 3600 1000))

(def free-credits 1000)

(def starter-credits 5000)
(def starter-users 3)

(def pro-credits 30000)

(def plan-types #{:basic :startup :pro})
