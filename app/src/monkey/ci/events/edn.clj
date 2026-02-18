(ns monkey.ci.events.edn
  "Functionality for reading events from an edn reader.  This is used in
   container jobs, where the script writes events about the commands to
   a shared file."
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci.edn :as edn])
  (:import java.io.PushbackReader))

(defn read-next [r]
  (edn/edn-> r {:eof ::eof}))

(def eof? (partial = ::eof))

(defn- as-pushback [r]
  (cond-> r
    (not (instance? PushbackReader r)) (PushbackReader.)))

(defn stop-at-eof
  "Wraps the given callback to return `false` on `::eof`"
  [h]
  (fn [evt]
    (if (eof? evt)
      false
      (h evt))))

(defn sleep-on-eof
  "Wraps the handler to sleep whenever eof is encountered"
  [h interval]
  (fn [evt]
    (if (eof? evt)
      (do
        ;; EOF reached, wait a bit and retry
        (Thread/sleep interval)
        true)
      (h evt))))

(defn stop-on-file-delete
  "Wraps the handler but stops when eof is encountered and the given file has
   been deleted."
  [h f]
  (fn [evt]
    (if (and (eof? evt) (not (fs/exists? f)))
      false
      (h evt))))

(defn read-edn
  "Reads lines from the given reader, and passes each of them as parsed edn 
   to the callback fn.  If the callback returns `false` or throws an error, 
   the async process is terminated.  On EOF, the callback is passed `::eof`
   and not any data read."
  [reader callback]
  (let [r (as-pushback reader)]
    (md/future
      (loop [evt (read-next r)]
        (when
            (try
              (callback evt)
              (catch Exception ex
                (log/error "Unable to read next event from reader" ex)
                false))
            (recur (read-next r))))
      (log/debug "Finished reading events from reader"))))
