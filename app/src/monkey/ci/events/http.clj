(ns monkey.ci.events.http
  "Implementation for events that connect to a HTTP server as a client, or that
   function as a HTTP server where clients connect to."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.ci.events.core :as c]
            [monkey.ci.utils :as u]
            [org.httpkit.client :as http])
  (:import java.io.PushbackReader))

(defn- pipe-events [r l]
  (let [read-next (fn [] (u/parse-edn r {:eof ::done}))]
    (loop [m (read-next)]
      (if (= ::done m)
        (do
          (log/info "Event stream closed"))
        (do
          (log/debug "Got event:" m)
          (l m)
          (recur (read-next)))))))

(deftype HttpClientEvents [url listening-streams]
  c/EventPoster
  (post-events [this evt]
    (let [b (prn-str evt)]
      @(http/post url {:body b
                       :headers {"content-type" "application/edn"
                                 "content-length" (str (count b))}
                       :method :post}))
    this)

  c/EventReceiver
  (add-listener [this l]
    (http/get url {:as :stream}
              (fn [{:keys [body status]}]
                (if (not= 200 status)
                  (log/warn "Request failed with status" status)
                  (do
                    (log/debug "Processing event stream...")
                    (swap! listening-streams assoc l body)
                    (with-open [r (-> body io/reader (PushbackReader.))]
                      (pipe-events r l))
                    (log/debug "Done processing event stream")))))
    this)

  (remove-listener [this l]
    (when-let [s (get @listening-streams l)]
      (.close s)
      (swap! listening-streams dissoc l))
    this))

(defn make-http-client
  "Creates a http client events object.  Any events posted to it will be sent
   to the remote HTTP server using a request.  When adding a listener, it will
   open a streamed HTTP request to the remote server."
  [url]
  (->HttpClientEvents url (atom {})))

;; (deftype SocketClientEvents [socket]
;;   c/EventPoster
;;   (post-events [this evt]
;;     ;; TODO
;;     this)

;;   c/EventReceiver
;;   (add-listener [this l]
;;     ;; TODO
;;     this)

;;   (remove-listener [this l]
;;     ;; TODO
;;     this))

;; (defn make-socket-client
;;   "Creates a http socket client events object.  This is only able to send
;;    events, not receive them.  This is because http-kit is not able to 
;;    receive http streams, whereas aleph can't handle socket connections.
;;    Alternatively, we would have to use websockets instead."
;;   [socket]
;;   (->SocketClientEvents socket))
