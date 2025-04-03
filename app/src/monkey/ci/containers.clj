(ns monkey.ci.containers
  "Generic functionality for running containers")

(def image (some-fn :container/image :image))
(def env :container/env)
(def cmd :container/cmd)
(def args :container/args)
(def mounts :container/mounts)
(def entrypoint :container/entrypoint)
(def platform :container/platform)

(def props
  "Serializable properties for container jobs"
  [:image :container/image env cmd args entrypoint])

(def base-cmd
  "Base command line for app processes"
  ["java" "-cp" "monkeyci.jar"
   "-Dlogback.configurationFile=config/logback.xml"
   "monkey.ci.core"])

(defn make-cmd [& args]
  (vec (concat base-cmd args)))
