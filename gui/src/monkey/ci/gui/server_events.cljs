(ns monkey.ci.gui.server-events
  "Read server-sent events from the API in an async channel"
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [<!]]
            [monkey.ci.gui.martian :as m]))

(defn read-events []
  (let [src (js/EventSource. (str m/url "/events") (clj->js {}))]
    (println "Event source:" src)
    (set! (.-onMessage src) (fn [evt]
                              (println "Got event:" evt)))))

(defn stop-reading-events [src]
  (.close src))
