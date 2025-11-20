(ns monkey.ci.mailing.scw
  "Scaleway implementation of the Mailer, that uses transactional emails to send mails."
  (:require [com.stuartsierra.component :as co]
            [martian.core :as mc]
            [medley.core :as m]
            [monkey.ci.protocols :as p]
            [monkey.scw.core :as scw]))

(defrecord ScwMailer [config]
  co/Lifecycle
  (start [this]
    (assoc this :ctx (scw/email-ctx config)))

  (stop [this]
    (dissoc this :ctx))
  
  p/Mailer
  (send-mail [this {:keys [subject text-body html-body destinations]}]
    (let [uh (:unsubscribe config)]
      (->> (select-keys config [:project-id :from :bcc])
           (merge (cond-> {:to (map (partial hash-map :email) destinations)
                           :subject subject
                           :text text-body
                           :html html-body}
                    uh (assoc :additional-headers
                              [{:key "list-unsubscribe"
                                :value uh}])))
           (m/filter-vals some?)
           (hash-map :region (:region config) :body)
           (mc/response-for (:ctx this) :create-email)
           (deref)
           :body))))
