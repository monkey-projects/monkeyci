(ns monkey.ci.events.http
  "Implementation for events that connect to a HTTP server as a client, or that
   function as a HTTP server where clients connect to."
  (:require [monkey.ci.events.core :as c]))

(deftype HttpClientEvents [url]
  c/EventPoster
  (post-events [this evt])

  c/EventReceiver
  (add-listener [this l])

  (remove-listener [this l]))

(defn make-http-client
  "Creates a http client events object.  Any events posted to it will be sent
   to the remote HTTP server using a request.  When adding a listener, it will
   open a streamed HTTP request to the remote server."
  [url]
  (->HttpClientEvents url))
