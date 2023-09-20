(ns monkey.ci.runners
  "Defines runner functionality.  These depend on the application configuration.
   A runner is able to execute a build script."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.ci.utils :as u]))

(defn- get-absolute-dirs [{:keys [dir workdir]}]
  (let [wd (io/file (or workdir (u/cwd)))]
    {:script-dir (some->> dir
                          (u/abs-path wd)
                          (io/file)
                          (.getCanonicalPath))
     :work-dir (some-> wd (.getCanonicalPath))}))

(defn build-local
  "Locates the build script locally and dispatches another event with the
   script details in them.  If no build script is found, dispatches a build
   complete event."
  [evt]
  (let [{:keys [script-dir work-dir] :as dirs} (get-absolute-dirs evt)]
    (if (some-> (io/file script-dir) (.exists))
      (-> evt
          (assoc :type :build/local)
          (merge dirs)
          (dissoc :dir :workdir))
      {:type :build/completed
       :exit 1
       :message (str "No build script found at " script-dir)
       :result :warning})))

(defn build-completed [{:keys [result exit] :as evt}]
  ;; Do some logging depending on the result
  (condp = (or result :unknown)
    :success (log/info "Success!")
    :warning (log/warn "Exited with warnings:" (:message evt))
    :error   (log/error "Failure.")
    :unknown (log/warn "Unknown result."))
  {:type :command/completed
   :command :build
   :result evt
   :exit exit})
