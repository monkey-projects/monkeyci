(ns logging
  (:require [monkey.ci.logging.log-ingest :as li]
            [monkey.ci.time :as t])
  (:import ch.qos.logback.classic.joran.JoranConfigurator
           org.slf4j.LoggerFactory))

(defn use-logback-config
  "Reconfigures logback to use config from given file"
  [f]
  (let [ctx (LoggerFactory/getILoggerFactory)
        conf (JoranConfigurator.)]
    (.setContext conf ctx)
    (.reset ctx)
    (.doConfigure conf f)))

(defn fetch-logs [{:keys [client path]}]
  (let [c (li/make-client client)]
    (li/fetch-logs c path)))

(defn push-logs [{:keys [client path]} data]
  (let [c (li/make-client client)]
    (li/push-logs c path [{:ts (t/now)
                           :contents data}])))
