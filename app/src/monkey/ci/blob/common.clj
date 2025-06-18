(ns monkey.ci.blob.common
  (:require [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clompress.archivers :as ca]
            [monkey.ci.build.archive :as a]
            [monkey.ci.utils :as u]))

(def compression-type "gz")
(def archive-type "tar")

(defn- drop-prefix-resolver
  "The default entry name resolver includes the full path to the file.  
   We only want the file name without the base directory, so that's what
   this resolver does."
  [base-dir path]
  (if (= path base-dir)
    "."
    ;; Skip the /
    (subs path (inc (count base-dir)))))

(defn- entry-gathering-resolver
  "Adds artifact entries to the given atom"
  [entries]
  (fn [p]
    (swap! entries conj p)
    p))

(defn- set-mode
  "Sets the TAR entry file mode using the posix file permissions"
  [entry]
  (.setMode entry (-> (.getFile entry)
                      (fs/posix-file-permissions)
                      (a/posix->mode))))

(defn make-archive
  "Archives the `src` directory or file into `dest`, which should be something
   that can be converted into an `OutputStream`."
  [src dest]
  (when-not (fs/exists? src)
    (throw (ex-info (str "Unable to make archive, path not found:" src) {:src src :dest dest})))
  ;; The prefix to drop is the directory where the files are in.  If the source is
  ;; a single file, we mean its containing directory, otherwise the entire directory
  ;; should be dropped.
  (let [prefix (u/abs-path
                (cond-> src
                  (not (fs/directory? src)) (fs/parent)))
        entries (atom [])
        gatherer (entry-gathering-resolver entries)]
    (log/debug "Archiving" src "and stripping prefix" prefix)
    (u/ensure-dir-exists! dest)
    (with-open [os (bs/to-output-stream dest)]
      (ca/archive
       {:output-stream os
        :compression compression-type
        :archive-type archive-type
        :entry-name-resolver (comp gatherer (partial drop-prefix-resolver prefix))
        :before-add set-mode}
       (u/abs-path src)))
    ;; Return some info, since clompress returns `nil`
    {:src src
     :dest dest
     :entries @entries}))

(defn tmp-dir [{:keys [tmp-dir]}]
  (or tmp-dir (u/tmp-dir)))

(def extension ".tgz")

(defn tmp-archive [conf]
  (io/file (tmp-dir conf) (str (random-uuid) extension)))
