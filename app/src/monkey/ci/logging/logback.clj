(ns monkey.ci.logging.logback
  "Utility functions for Logback"
  (:import [ch.qos.logback.classic.joran JoranConfigurator]
           [java.io ByteArrayInputStream]
           [org.slf4j LoggerFactory]))

(defn configure-logback
  "Configures logback using the configuration in the argument, instead of from a file."
  [config]
  (with-open [s (ByteArrayInputStream. (.getBytes config "UTF-8"))]
    (let [ctx (LoggerFactory/getILoggerFactory)]
      (.reset ctx)
      (doto (JoranConfigurator.)
        (.setContext ctx)
        (.doConfigure s)))))

(defn configure-from-env
  "Configures logback using the configuration in the given env var"
  [env]
  (some-> (System/getenv env)
          (configure-logback)))
