(ns monkey.ci.events.http
  "Implementation for events that connect to a HTTP server as a client, or that
   function as a HTTP server where clients connect to."
  (:require [clj-commons.byte-streams :as bs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci.events.core :as c]
            [monkey.ci.utils :as u]
            [aleph.http :as ah])
  (:import java.io.PushbackReader))

(defn- start-thread [f]
  (doto (Thread. f)
    (.start)))

(defn- pipe-events [r l]
  (try 
    (let [pr (PushbackReader. r)
          read-next (fn [] (edn/read {:eof ::done} pr))]
      (loop [m (read-next)]
        (if (= ::done m)
          (do
            (log/info "Event stream closed"))
          (do
            (log/debug "Got event:" m)
            (l m)
            (recur (read-next)))))
      (log/debug "Done piping events"))
    (catch InterruptedException ex
      (log/debug "Event piping interrupted"))
    (catch Exception ex
      (log/error "Failed to pipe events:" ex))))

(deftype HttpClientEvents [url pool listening-streams]
  c/EventPoster
  (post-events [this evt]
    (let [b (prn-str evt)]
      @(ah/post url {:body b
                     :headers {"content-type" "application/edn"
                               "content-length" (str (count b))}
                     :method :post}))
    this)

  c/EventReceiver
  (add-listener [this l]
    (-> (md/chain
         (ah/get url {:pool pool})
         :body
         bs/to-reader)
        (md/on-realized
         (fn [r]
           (log/debug "Processing event stream...")
           (swap! listening-streams assoc l {:reader r
                                             :thread (start-thread #(pipe-events r l))}))
         (fn [err]
           (log/error "Unable to receive server events:" err))))
    (log/debug "Listener added")
    this)

  (remove-listener [this l]
    (when-let [{r :reader t :thread :as m} (get @listening-streams l)]
      (future
        (log/debug "Closing reader:" r)
        ;; Closing this reader blocks, so we do it in a separate thread and interrupt the
        ;; pipe thread as well.
        (.close r)
        (log/debug "Reader closed"))
      (.interrupt t)
      (swap! listening-streams dissoc l))
    (log/debug "Listener removed")
    this))

(defn make-http-client
  "Creates a http client events object.  Any events posted to it will be sent
   to the remote HTTP server using a request.  When adding a listener, it will
   open a streamed HTTP request to the remote server."
  [url]
  (->HttpClientEvents url
                      (ah/connection-pool {:connection-options {:raw-stream? true}})
                      (atom {})))

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
