(ns monkey.ci.events.http
  "Implementation for events that connect to a HTTP server as a client, or that
   function as a HTTP server where clients connect to."
  (:require [monkey.ci.events.core :as c]))

(deftype HttpClientEvents [url]
  c/EventPoster
  (post-events [this evt]
    ;; TODO
    this)

  c/EventReceiver
  (add-listener [this l]
    ;; TODO
    this)

  (remove-listener [this l]
    ;; TODO
    this))

(defn make-http-client
  "Creates a http client events object.  Any events posted to it will be sent
   to the remote HTTP server using a request.  When adding a listener, it will
   open a streamed HTTP request to the remote server."
  [url]
  (->HttpClientEvents url))

(deftype SocketClientEvents [socket]
  c/EventPoster
  (post-events [this evt]
    ;; TODO
    this)

  c/EventReceiver
  (add-listener [this l]
    ;; TODO
    this)

  (remove-listener [this l]
    ;; TODO
    this))

(defn make-socket-client
  "Creates a http socket client events object.  This is only able to send
   events, not receive them.  This is because http-kit is not able to 
   receive http streams, whereas aleph can't handle socket connections.
   Alternatively, we would have to use websockets instead."
  [socket]
  (->SocketClientEvents socket))
