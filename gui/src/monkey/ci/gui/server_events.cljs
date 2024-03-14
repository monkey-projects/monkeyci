(ns monkey.ci.gui.server-events
  "Read server-sent events from the API and dispatch events"
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :refer [<!]]
            [clojure.tools.reader.edn :as edn]
            [monkey.ci.gui.martian :as m]
            [re-frame.core :as rf]))

(defn- parse-edn [edn]
  (try
    (edn/read-string edn)
    (catch js/Error ex
      (println "Unable to parse edn:" ex)
      (println "Edn:" edn))))

(def events-url
  (if (exists? js/eventsRoot)
    js/eventsRoot
    m/url))

(defn read-events
  "Creates an event source that reads from the events endpoint.  Dispatches an
   event using `on-recv-event` with the received message appended."
  [cust-id token on-recv-evt]
  (if cust-id
    (do
      (println "Starting reading events for customer" cust-id)
      ;; Send security token as query arg.
      ;; Alternatively, and perhaps more secure, would be to add an extra endpoint where
      ;; the client can retrieve a short-lived "event token", only valid for events and
      ;; pass that here.  That way, the client wouldn't have to send its longer lived
      ;; token as query arg (which may be logged).
      (let [src (js/EventSource. (str events-url "/customer/" cust-id "/events?authorization=" token)
                                 (clj->js {}))]
        (set! (.-onmessage src)
              (fn [evt]
                (let [d (parse-edn (.-data evt))]
                  (println "Got event:" d)
                  (rf/dispatch (conj on-recv-evt d)))))
        src))
    (println "No customer id provided")))

(defn stop-reading-events [src]
  (when src
    (.close src)))

(defn stream-config [db id]
  (get-in db [::event-stream id]))

(defn set-stream-config [db id c]
  (assoc-in db [::event-stream id] c))

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
 (fn [{:keys [db] :as ctx} [_ id cust-id handler-evt]]
   (println "Starting event stream:" id)
   (let [conn (::connector ctx)
         token (:auth/token db)]
     {:db (set-stream-config db id {:handler-evt handler-evt
                                    :source (conn cust-id token handler-evt)})})))

(rf/reg-event-fx
 :event-stream/stop
 (fn [{:keys [db]} [_ id]]
   (println "Stopping event stream:" id)
   {:db (update db ::event-stream dissoc id)
    :event-stream/close (:source (stream-config db id))}))
