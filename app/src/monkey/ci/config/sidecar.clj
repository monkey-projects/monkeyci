(ns monkey.ci.config.sidecar
  "Functions for handling sidecar configuration"
  (:require [medley.core :as mc]
            [monkey.ci.spec :as s]
            [monkey.ci.spec
             [blob :as bs]
             [events :as es]
             [sidecar :as ss]]))

(defn- ns-keys [m]
  (mc/map-keys (comp (partial keyword "monkey.ci.spec.sidecar") name)))

(defn rt->sidecar-config
  "Creates sidecar configuration object from the runtime"
  [rt]
  (merge
   (ns-keys (get-in rt [:config :sidecar]))
   {::ss/events (:events rt)
    ::ss/build (:build rt)
    ::ss/log-maker (get-in rt [:logging :maker])}))

(defn set-job [conf job]
  (assoc-in conf [::ss/job-config ::ss/job] job))

(def job (comp ::ss/job ::ss/job-config))

(defn set-build [conf build]
  (assoc-in conf [::ss/job-config ::ss/build] build))

(def build (comp ::ss/build ::ss/job-config))

(def api ::es/api)

(defn set-api [conf api]
  (assoc conf ::es/api api))

(def events ::es/events)

(defn set-events [conf e]
  (assoc conf ::es/events e))

(def log-maker ::ss/log-maker)

(defn set-log-maker [conf l]
  (assoc conf ::ss/log-maker l))

(def default-poll-interval 1000)

(defn poll-interval [conf]
  (get conf ::ss/poll-interval default-poll-interval))

(defn set-poll-interval [conf v]
  (assoc conf ::ss/poll-interval v))

(def events-file ::ss/events-file)

(defn set-events-file [conf f]
  (assoc conf ::ss/events-file f))
                       
(def start-file ::ss/start-file)

(defn set-start-file [conf f]
  (assoc conf ::ss/start-file f))

(def abort-file ::ss/abort-file)

(defn set-abort-file [conf f]
  (assoc conf ::ss/abort-file f))
                       
(def workspace ::bs/workspace)

(defn set-workspace [conf ws]
  (assoc conf ::bs/workspace ws))
                       
(def artifacts ::bs/artifacts)

(defn set-artifacts [conf ws]
  (assoc conf ::bs/artifacts ws))
                       
(def cache ::bs/cache)

(defn set-cache [conf ws]
  (assoc conf ::bs/cache ws))
