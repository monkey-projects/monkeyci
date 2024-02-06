(ns monkey.ci.events.http
  "Implementation for events that connect to a HTTP server as a client.  These
   cannot be used to receive events from, use websockets instead."
  (:require [clojure.tools.logging :as log]
            [monkey.ci.events.core :as c]
            [aleph.http :as ah]))

(deftype HttpClientEvents [url]
  c/EventPoster
  (post-events [this evt]
    (let [v (if (sequential? evt) evt [evt])
          b (prn-str v)]
      (log/debug "Posting" (count v) "events")
      @(ah/post url {:body b
                     :headers {"content-type" "application/edn"
                               "content-length" (str (count b))}
                     :method :post}))
    ;; TODO Return a value that can be inspected instead
    this))

(defn make-http-client
  "Creates a http client events object.  Any events posted to it will be sent
   to the remote HTTP server using a request.  When adding a listener, it will
   open a streamed HTTP request to the remote server."
  [url]
  (->HttpClientEvents url))

(defmethod c/make-events :http [{{:keys [url]} :events}]
  (make-http-client url))
