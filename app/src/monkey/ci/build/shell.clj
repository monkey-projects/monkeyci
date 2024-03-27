(ns monkey.ci.build.shell
  (:require [babashka
             [fs :as fs]
             [process :as bp]]
            [clojure.tools.logging :as log]
            [monkey.ci.build
             [api :as api]
             [core :as core]]
            [monkey.ci.oci :as oc]))

(defn bash [& args]
  (core/as-job
   (fn [rt]
     (let [work-dir (or (get-in rt [:job :work-dir]) (:checkout-dir rt))]
       (log/debug "Executing shell script with args" args "in work dir" work-dir)
       (try
         (let [opts (cond-> {:out :string
                             :err :string}
                      ;; Add work dir if specified in the context
                      work-dir (assoc :dir work-dir))]
           (assoc core/success
                  :output (:out (apply bp/shell opts args))))
         (catch Exception ex
           (let [{:keys [out err]} (ex-data ex)]
             ;; Report the error
             (assoc core/failure
                    :output out
                    :error err
                    :exception ex))))))))

(def home (System/getProperty "user.home"))

(defn param-to-file
  "Takes the value of `param` from the build parameters and writes it to 
   the file at path `out`.  Creates any directories as needed.  Returns 
   `nil` if the operation succeeded."
  [ctx param out]
  (let [v (get (api/build-params ctx) param)
        f (fs/file (fs/expand-home out))
        p (fs/file (fs/parent f))
        d? (or (nil? p) (fs/exists? p) (.mkdirs p))]
    (if (and v d?)
      (spit f v)
      (assoc core/failure :message (if v
                                     (str "Unable to create directory " p)
                                     (str "No parameter value found for " param))))))

(defn in-work
  "Given a relative path `p`, returns it as a subpath to the job working directory.
   Fails if an absolute path is given."
  [ctx p]
  (let [wd (core/work-dir ctx)]
    (str (fs/normalize (fs/path wd p)))))

(def container-work-dir
  "Returns the dir where the workspace would be mounted in a container."
  ;; TODO Get this from runtime instead, because now this depends on driver type
  oc/base-work-dir)
