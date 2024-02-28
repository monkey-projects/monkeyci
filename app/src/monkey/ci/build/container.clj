(ns monkey.ci.build.container
  "Functions to configure container images on a build step")

(defn image [step img]
  (assoc step :container/image img))
