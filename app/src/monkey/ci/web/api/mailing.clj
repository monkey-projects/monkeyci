(ns monkey.ci.web.api.mailing
  "Mailing api handlers"
  (:require [clojure.tools.logging :as log]
            [monkey.ci
             [protocols :as p]
             [storage :as st]
             [time :as t]]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(def mailing-id (comp :mailing-id :path :parameters))

(c/make-entity-endpoints
 "mailing"
 {:get-id (comp :mailing-id :path :parameters)
  :getter st/find-mailing
  :saver st/save-mailing
  :deleter st/delete-mailing})

(defn create-mailing [req]
  (let [ec (c/entity-creator st/save-mailing c/default-id)]
    (-> req
        (assoc-in [:parameters :body :creation-time] (t/now))
        (ec))))

(defn list-mailings [req]
  (let [m (st/list-mailings (c/req->storage req))]
    (-> (rur/response m)
        (rur/status (if (empty? m) 204 200)))))

(c/make-entity-endpoints
 "sent-mailing"
 {:get-id (comp (juxt :mailing-id :sent-mailing-id) :path :parameters)
  :getter st/find-mailing
  :saver st/save-mailing})

(defn list-destinations
  "Retrieves all relevant emails according to the sent mail configuration.  This
   could be emails from users, email registrations and/or additional custom emails."
  [st m]
  (concat
   (when (:to-subscribers m)
     (->> (st/list-email-registrations st)
          (map :email)))
   (when (:to-users m)
     (st/list-user-emails st))
   (:other-dests m)))

(defn create-sent-mailing [req]
  (let [b (c/body req)
        st (c/req->storage req)
        mid (mailing-id req)
        m (st/find-mailing st mid)
        mailer (:mailer (c/req->rt req))
        mail (when mailer
               (-> (select-keys m [:subject :html-body :text-body])
                   (assoc :destinations (list-destinations st b))
                   (as-> m (p/send-mail mailer m))))
        r (cond-> (assoc b :id (st/new-id) :mailing-id mid :sent-at (t/now))
            ;; FIXME scw returns list of emails, not a single id
            mail (assoc :mail-id (:id mail)))]
    (if mailer
      (log/debug "Sending mailing" mid "to" (count (:destinations mail)) "destinations")
      (log/warn "No mailer configured, emails will not be sent."))
    (if-let [sid (st/save-sent-mailing st r)]
      (-> (rur/response (assoc r :id (last sid)))
          (rur/status 201))
      (-> (rur/response {:message "unable to save mailing to database"})
          (rur/status 500)))))

(defn list-sent-mailings [req]
  (let [sm (st/list-sent-mailings (c/req->storage req)
                                  (mailing-id req))]
    (-> (rur/response sm)
        (rur/status (if (empty? sm) 204 200)))))
