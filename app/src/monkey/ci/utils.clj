(ns monkey.ci.utils
  (:require [buddy.core.keys.pem :as pem]
            [clojure.core.async :as ca]
            [clojure
             [edn :as edn]
             [string :as cs]
             [walk :as cw]]
            [clojure.java.io :as io]
            [clojure.repl :as cr]
            [medley.core :as mc])
  (:import org.apache.commons.io.FileUtils))

(defn cwd
  "Returns current directory"
  []
  (System/getProperty "user.dir"))

(defn abs-path
  "If `b` is a relative path, will combine it with `a`, otherwise
   will just return `b`."
  ([a b]
   (if a
     (if (.isAbsolute (io/file b))
       b
       (str (io/file a b)))
     b))
  ([p]
   (some-> p
           (io/file)
           (.getCanonicalPath))))

(defn combine
  "Returns the canonical path of combining `a` and `b`"
  [a b]
  (.getCanonicalPath (io/file a b)))

(defn add-shutdown-hook!
  "Executes `h` when the JVM shuts down.  Returns the thread that will
   execute the hook."
  [h]
  (let [t (Thread. h)]
    (.. (Runtime/getRuntime)
        (addShutdownHook t))
    t))

(defn tmp-dir []
  (System/getProperty "java.io.tmpdir"))

(defn tmp-file
  "Generates a new temporary path"
  ([prefix suffix]
   (tmp-file (str prefix (random-uuid) suffix)))
  ([name]
   (-> (io/file (tmp-dir) name)
       (.getAbsolutePath))))

(defn delete-dir
  "Deletes directory recursively"
  [dir]
  (FileUtils/deleteDirectory (io/file dir)))

(defn new-build-id []
  ;; TODO Generate a more unique build id
  (format "build-%d" (System/currentTimeMillis)))

#_(defn replace-last
  "Replaces the last item in `v` by `l`"
  [v l]
  (-> v
      (drop-last)
      (conj l)))

(defn load-privkey
  "Load private key from file"
  [f]
  (if (instance? java.security.PrivateKey f)
    f
    (with-open [r (io/reader f)]
      (pem/read-privkey r nil))))

(defn future->ch
  "Returns a channel that will hold the future value.  The future value
   cannot be `nil` as this value cannot be sent to a channel.  If `interval`
   is not specified, 100 milliseconds is used between checks."
  [f & [interval]]
  (let [poll (fn []
               (when (realized? f)
                 @f))]
    (ca/go-loop [v (poll)]
      (if v
        v
        (do
          (ca/<! (ca/timeout (or interval 100)))
          (recur (poll)))))))

(defn parse-edn
  "Parses edn from the reader"
  [r & [opts]]
  (edn/read (or opts {}) (java.io.PushbackReader. r)))

(defn fn-name
  "If x points to a fn, returns its name without namespace"
  [x]
  (->> (str x)
       (cr/demunge)
       (re-find #".*\/(.*)[\-\-|@].*")
       (second)))

(defn stack-trace
  "Prints stack trace to a string"
  [^Exception ex]
  (let [sw (java.io.StringWriter.)
        pw (java.io.PrintWriter. sw)]
    (.printStackTrace ex pw)
    (.flush pw)
    (.toString sw)))

(defn- prune-map [m]
  ;; Remove nil values and empty strings
  (mc/remove-vals (some-fn nil? (every-pred seqable? empty?) (every-pred string? empty?)) m))

(defn prune-tree [t]
  (cw/prewalk (fn [x]
                (cond-> x
                  (map? x) (prune-map)))
              t))

(defn- merge-if-map [a b]
  (if (map? a)
    (merge a b)
    b))

(def deep-merge (partial merge-with merge-if-map))

(defn ->base64 [s]
  (.. (java.util.Base64/getEncoder)
      (encodeToString (.getBytes s java.nio.charset.StandardCharsets/UTF_8))))

(defn parse-sid [s]
  (when s
    (cs/split s #"/")))

(def serialize-sid (partial cs/join "/"))
