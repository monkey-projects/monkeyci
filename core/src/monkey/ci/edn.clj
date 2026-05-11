(ns monkey.ci.edn
  "Minimal EDN reader helpers, GraalVM-native-image compatible.
   Uses clojure.edn directly; no external dependencies."
  (:import [java.io PushbackReader]))

(defn edn->
  "Reads the next EDN value from `reader`.  `reader` must be a java.io.Reader
   (typically a PushbackReader).  Returns `(get opts :eof nil)` when the end
   of the stream is reached.  Throws on malformed input."
  ([reader]
   (edn-> reader {}))
  ([reader opts]
   (let [r (cond-> reader
              (not (instance? PushbackReader reader)) (PushbackReader.))]
     (clojure.edn/read (assoc opts :eof (get opts :eof ::eof)) r))))
