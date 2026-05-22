(ns monkey.ci.utils.io
  "I/O utility functions, GraalVM-native-image compatible.
   Replaces the manifold-based helpers in monkey.ci.utils (app/)."
  (:require [babashka.fs :as fs]))

(defn wait-for-file
  "Blocks the calling thread, polling every `period` ms until `f` exists.
   Returns `f` when it appears.

   Unlike the app/ version (which uses manifold.stream/periodically), this
   function is synchronous and should be called from inside a background thread
   (e.g. the thread started by `monkey.ci.events.edn/read-edn`)."
  [f & {:keys [period] :or {period 200}}]
  (loop []
    (if (fs/exists? f)
      f
      (do (Thread/sleep ^long period) (recur)))))
