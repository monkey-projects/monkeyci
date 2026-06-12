(ns monkey.ci.edn
  "Minimal EDN reader helpers, GraalVM-native-image compatible.
   Uses clojure.edn directly; no external dependencies."
  (:require [clojure.java.io :as io])
  (:import [java.io PushbackReader]))

(defn edn->
  "Reads the next EDN value from `reader`.  `reader` must be a java.io.Reader
   (typically a PushbackReader).  Returns `(get opts :eof nil)` when the end
   of the stream is reached.  Throws on malformed input."
  ([src]
   (edn-> src {}))
  ([src opts]
   (if (string? src)
     (clojure.edn/read-string opts src)
     (let [r (cond-> (io/reader src)
               (not (instance? PushbackReader src)) (PushbackReader.))]
       (clojure.edn/read (assoc opts :eof (get opts :eof ::eof)) r)))))

(def ->edn pr-str)
