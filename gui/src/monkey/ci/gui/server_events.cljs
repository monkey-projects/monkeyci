(ns monkey.ci.gui.server-events
  "Read server-sent events from the API in an async channel"
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [<!]]
            [clojure.tools.reader.edn :as edn]
            [monkey.ci.gui.martian :as m]
            [re-frame.core :as rf]))

(defn- parse-edn [edn]
  (edn/read-string edn))

(defn read-events [on-recv-evt]
  (let [src (js/EventSource. (str m/url "/events") (clj->js {}))]
    (println "Event source:" src)
    (set! (.-onmessage src)
          (fn [evt]
            (let [d (parse-edn (.-data evt))]
              (println "Got event:" d)
              (rf/dispatch (conj on-recv-evt d)))))
    src))

(defn stop-reading-events [src]
  (.close src))

(rf/reg-fx
 :event-stream/close
 (fn [src]
   (stop-reading-events src)))

(rf/reg-event-fx
 :event-stream/start
 (fn [{:keys [db]} [_ id handler-evt]]
   {:db (assoc-in db [::event-stream id] {:handler-evt handler-evt
                                          :source (read-events handler-evt)})}))

(rf/reg-event-fx
 :event-stream/stop
 (fn [{:keys [db]} [_ id]]
   {:db (update db ::event-stream dissoc id)
    :event-stream/close (get-in db [::event-stream id :source])}))
