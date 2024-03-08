(ns monkey.ci.gui.server-events
  "Read server-sent events from the API and dispatch events"
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [<!]]
            [clojure.tools.reader.edn :as edn]
            [monkey.ci.gui.martian :as m]
            [re-frame.core :as rf]))

(defn- parse-edn [edn]
  (edn/read-string edn))

(defn read-events
  "Creates an event source that reads from the events endpoint.  Dispatches an
   event using `on-recv-event` with the received message appended."
  [on-recv-evt]
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

(rf/reg-cofx
 :event-stream/connector
 (fn [cofx _]
   (assoc cofx ::connector read-events)))

(rf/reg-fx
 :event-stream/close
 (fn [src]
   (stop-reading-events src)))

(rf/reg-event-fx
 :event-stream/start
 [(rf/inject-cofx :event-stream/connector)]
 (fn [{:keys [db] :as ctx} [_ id handler-evt]]
   (let [conn (::connector ctx)]
     {:db (assoc-in db [::event-stream id] {:handler-evt handler-evt
                                            :source (conn handler-evt)})})))

(rf/reg-event-fx
 :event-stream/stop
 (fn [{:keys [db]} [_ id]]
   {:db (update db ::event-stream dissoc id)
    :event-stream/close (get-in db [::event-stream id :source])}))
