(ns logging
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
