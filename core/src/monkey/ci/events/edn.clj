(ns monkey.ci.events.edn
  "Reads events from an EDN reader.  Used by container jobs where the job script
   writes events about executed commands to a shared file.

   GraalVM-native-compatible reimplementation of monkey.ci.events.edn from app/.
   The key difference: instead of manifold deferreds, `read-edn` runs its loop
   inside a plain daemon thread."
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [monkey.ci.edn :as edn])
  (:import [java.io InputStream InputStreamReader PushbackReader]))

(def ^:private sentinel ::eof)

(defn read-next
  "Reads the next EDN value from `r`.  Returns `::eof` at end-of-stream."
  [r]
  (edn/edn-> r {:eof sentinel}))

(def eof?
  "True when the value returned by `read-next` signals end-of-stream."
  (partial = sentinel))

(defn stop-at-eof
  "Wraps a callback handler so that it returns `false` (stopping the loop)
   when an EOF sentinel is received."
  [h]
  (fn [evt]
    (if (eof? evt) false (h evt))))

(defn sleep-on-eof
  "Wraps a callback handler so that it sleeps `interval` ms and returns `true`
   (continuing the loop) whenever an EOF sentinel is received.  Use this when
   tailing a file that is still being written."
  [h interval]
  (fn [evt]
    (if (eof? evt)
      (do (Thread/sleep ^long interval) true)
      (h evt))))

(defn stop-on-file-delete
  "Wraps a callback handler so that it stops the loop when EOF is reached AND
   `f` no longer exists.  This lets the loop continue tailing a file until the
   writer deletes it to signal completion."
  [h f]
  (fn [evt]
    (if (and (eof? evt) (not (fs/exists? f)))
      false
      (h evt))))

(defn read-edn
  "Continuously reads EDN values from `reader` and passes each one to `callback`.

   The loop runs in a new daemon thread so it does not block the caller.  The loop
   stops when:
     - `callback` returns a falsy value, or
     - `callback` throws an exception.

   `callback` receives the parsed value, or `::eof` on end-of-stream.  Use the
   `stop-at-eof`, `sleep-on-eof`, and `stop-on-file-delete` wrappers to handle
   EOF cleanly.

   Returns the started thread (a `java.lang.Thread`) so callers can interrupt
   or join it if needed."
  [reader callback]
  (let [r (cond-> reader
            (instance? InputStream reader) (InputStreamReader.)
            (not (instance? PushbackReader reader)) (PushbackReader.))
        t (Thread.
           (fn []
             (loop [evt (read-next r)]
               (when
                   (try
                     (callback evt)
                     (catch Exception ex
                       (log/error "Unable to read next event from reader" ex)
                       false))
                   (recur (read-next r))))
             (log/debug "Finished reading events from reader")))]
    (doto t
      (.setDaemon true)
      (.start))))
