(ns monkey.ci.containers.common
  (:require [babashka.fs :as fs]
            [monkey.ci
             [build :as b]
             [utils :as u]]
            [monkey.ci.containers :as c]
            [monkey.ci.sidecar.config :as sc]))

(def home-dir "/home/monkeyci")
(def checkout-vol "checkout")
(def checkout-dir "/opt/monkeyci/checkout")
(def script-dir "/opt/monkeyci/script")
(def key-dir "/opt/monkeyci/keys")

(def script-vol "scripts")
(def job-script "job.sh")
(def config-vol "config")
(def config-dir "/home/monkeyci/config")
(def job-container-name "job")
(def config-file "config.edn")

(def sidecar-container-name "sidecar")

(def promtail-config-vol "promtail-config")
(def promtail-config-dir "/etc/promtail")
(def promtail-config-file "config.yml")

(defn checkout-subdir
  "Returns the path for `n` as a subdir of the checkout dir"
  [n]
  (str checkout-dir "/" n))

(def work-dir (checkout-subdir "work"))
(def log-dir (checkout-subdir "log"))
(def events-dir (checkout-subdir "events"))
(def start-file (str events-dir "/start"))
(def abort-file (str events-dir "/abort"))
(def event-file (str events-dir "/events.edn"))

(defn base-work-dir
  "Determines the base work dir to use inside the container"
  [build]
  (some->> (b/checkout-dir build)
           (fs/file-name)
           (fs/path work-dir)
           (str)))

(defn job-work-dir
  "The work dir to use for the job in the container.  This is the external job
   work dir, relative to the container checkout dir."
  [job build]
  (let [wd (:work-dir job)]
    (cond-> (base-work-dir build)
      wd (u/combine wd))))

(def sidecar-cmd
  "Sidecar command list"
  (c/make-cmd
   "-c" (str config-dir "/" config-file)
   "internal"
   "sidecar"
   ;; TODO Move this to config file
   "--events-file" event-file
   "--start-file" start-file
   "--abort-file" abort-file))

(defn make-sidecar-config
  "Creates a configuration map using the runtime, that can then be passed on to the
   sidecar container."
  [{:keys [build job] :as conf}]
  (-> {}
      (sc/set-build (-> build
                        (select-keys (conj b/sid-props :workspace))
                        (assoc :checkout-dir (base-work-dir build))))
      (sc/set-job (-> job
                      (select-keys [:id :save-artifacts :restore-artifacts :caches :dependencies])
                      (assoc :work-dir (job-work-dir job build))))
      (sc/set-events-file event-file)
      (sc/set-start-file start-file)
      (sc/set-abort-file abort-file)
      (sc/set-api (:api conf))))
