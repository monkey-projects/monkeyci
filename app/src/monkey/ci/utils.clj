(ns monkey.ci.utils
  (:require [babashka.fs :as fs]
            [buddy.core.keys.pem :as pem]
            [clojure
             [string :as cs]
             [walk :as cw]]
            [clojure.java.io :as io]
            [clojure
             [math :as math]
             [repl :as cr]]
            [medley.core :as mc]
            [monkey.ci
             [edn :as ce]
             [sid :as sid]])
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
    (when-not (.isDirectory f)
      (throw (ex-info "Directory cannot be created, already exists as a file" {:dir f})))
    (when-not (.mkdirs f)
      (throw (ex-info "Unable to create directory" {:dir f}))))
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
   (-> (io/file (tmp-dir) name)
       (.getAbsolutePath))))

(defn delete-dir
  "Deletes directory recursively"
  [dir]
  (FileUtils/deleteDirectory (io/file dir)))

;; Use build index instead
(defn ^:deprecated new-build-id []
  ;; TODO Generate a more unique build id
  (format "build-%d" (System/currentTimeMillis)))

(defn load-privkey
  "Load private key from file or from string"
  [f]
  (letfn [(->reader [x]
            (if (.exists (io/file x))
              (io/reader x)
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

(defn deep-merge
  "Recursively merges maps."
  ;; Copied from https://dnaeon.github.io/recursively-merging-maps-in-clojure/
  [& maps]
  (letfn [(m [& xs]
            (if (some #(and (map? %) (not (record? %))) xs)
              (apply merge-with m xs)
              (last xs)))]
    (reduce m maps)))

(defn ->base64 [s]
  (.. (java.util.Base64/getEncoder)
      (encodeToString (.getBytes s java.nio.charset.StandardCharsets/UTF_8))))

(def ^:deprecated parse-sid sid/parse-sid)
(def ^:deprecated sid->repo-sid sid/sid->repo-sid)

(def ^:deprecated serialize-sid sid/serialize-sid)

(defn prop-pred
  "Returns a fn that is a predicate to match property `p` with value `v`"
  [p v]
  (comp (partial = v) p))

(defn try-slurp
  "Reads the file if it exists, or just returns x"
  [x]
  (when-let [f (some-> x (io/file))]
    (if (.exists f)
      (slurp f)
      x)))

(defn now []
  (System/currentTimeMillis))

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
