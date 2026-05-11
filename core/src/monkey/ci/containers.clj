(ns monkey.ci.containers
  "Generic accessors for container job properties.
   All functions are pure keyword lookups — no external dependencies,
   fully GraalVM-native-image compatible.")

(def image
  "Returns the container image for a job.  Accepts both :container/image and legacy :image."
  (some-fn :container/image :image))

(def env
  "Returns the environment variable map for a container job."
  :container/env)

(def cmd
  "Returns the command override for a container job."
  :container/cmd)

(def args
  "Returns additional command arguments for a container job."
  :container/args)

(def mounts
  "Returns volume mount specifications for a container job."
  :container/mounts)

(def entrypoint
  "Returns the entrypoint override for a container job."
  :container/entrypoint)

(def platform
  "Returns the target platform for a container job."
  :container/platform)

(def arch
  "Returns the cpu architecture for a container job (:arm or :amd)."
  :arch)

(def props
  "Serializable properties present on container jobs."
  [:image :container/image env cmd args entrypoint])
