(ns monkey.ci.utils
  (:require [babashka.fs :as fs]
            [buddy.core
             [codecs :as bcc]
             [hash :as bch]]
            [buddy.core.keys.pem :as pem]
            [clojure
             [math :as math]
             [repl :as cr]
             [walk :as cw]]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [edn :as ce]
             [sid :as sid]
             [time :as t]]))

(defn cwd
  "Returns current directory"
  []
  (System/getProperty "user.dir"))

(defn abs-path
  "If `b` is a relative path, will combine it with `a`, otherwise
   will just return `b`."
  ([a b]
   (if a
     (if (fs/absolute? b)
       b
       (str (fs/path a b)))
     b))
  ([p]
   (some-> p
           (fs/canonicalize)
           (str))))

(defn combine
  "Returns the canonical path of combining `a` and `b`"
  [a b]
  (-> (fs/path a b)
      (fs/canonicalize)
      (str)))

(defn rebase-path
  "Given two absolute paths, recalculates p so that it becomes relative to `to` instead
   of `from`."
  [p from to]
  (str (if (fs/absolute? p)
         (->> (fs/relativize from p)
              (fs/path to))
         (fs/path to p))))

(defn mkdirs! [f]
  (if (and f (fs/exists? f))
    (when-not (fs/directory? f)
      (throw (ex-info "Directory cannot be created, already exists as a file" {:dir f})))
    (when-not (fs/create-dirs f)
      (throw (ex-info "Unable to create directory" {:dir f}))))
  f)

(defn ensure-dir-exists!
  "If `f` is a file, ensures that the directory containing `f` exists."
  [f]
  (let [p (some-> f fs/parent)]
    (when p
      (mkdirs! p)))
  f)

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
   (-> (fs/path (tmp-dir) name)
       (fs/absolutize)
       (str))))

(defn delete-dir
  "Deletes directory recursively"
  [dir]
  (fs/delete-tree dir))

(defn load-privkey
  "Load private key from file or from string"
  [f]
  (letfn [(->reader [x]
            (if (fs/exists? x)
              (io/reader (fs/file x))
              (java.io.StringReader. x)))]
    (if (instance? java.security.PrivateKey f)
      f
      (with-open [r (->reader f)]
        (pem/read-privkey r nil)))))

(def parse-edn
  "Parses edn from the reader"
  ce/edn->)

(defn parse-edn-str [s]
  (with-open [r (java.io.StringReader. s)]
    (parse-edn r)))

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

(defn ->base64 [s]
  (.. (java.util.Base64/getEncoder)
      (encodeToString (.getBytes s java.nio.charset.StandardCharsets/UTF_8))))

(def ^:deprecated parse-sid sid/parse-sid)
(def ^:deprecated sid->repo-sid sid/sid->repo-sid)

(def ^:deprecated serialize-sid sid/serialize-sid)

(defn try-slurp
  "Reads the file if it exists, or just returns x"
  [x]
  (if (fs/exists? x)
    (slurp x)
    x))

(def now t/now)

(defn ->seq
  "Converts `x` into a sequential"
  [x]
  (if (sequential? x) x [x]))

(defn or-nil
  "Wraps `f` so that when the argument is `nil`, it also returns `nil` and does not invoke `f`."
  [f]
  (fn [x]
    (when x
      (f x))))

(defn round-up
  "Rounds the decimal argument up to the next integer value"
  [x]
  (let [r (int (math/round x))]
    (cond-> r
      (< r x) inc)))

(defn log-deferred-elapsed
  "Given a deferred, keeps track of time elapsed and logs it"
  [x msg]
  (let [start (now)]
    (md/finally x (fn []
                    (let [elapsed (- (now) start)]
                      (log/debugf "%s - Elapsed: %s ms, %.2f s" msg elapsed (float (/ elapsed 1000))))))))

(defn file-hash
  "Calculates md5 hash for given file"
  [path]
  (when (fs/exists? path)
    (with-open [in (io/input-stream path)]
      (-> (bch/md5 in)
          (bcc/bytes->hex)))))

(defn maybe-deref [x]
  (cond-> x
    (md/deferred? x) (deref)))
