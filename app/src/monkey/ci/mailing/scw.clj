(ns monkey.ci.mailing.scw
  "Scaleway implementation of the Mailer, that uses transactional emails to send mails."
  (:require [com.stuartsierra.component :as co]
            [martian.core :as mc]
            [medley.core :as m]
            [monkey.ci
             [protocols :as p]
             [retry :as retry]]
            [monkey.scw.core :as scw]))

(defn- send-with-retry [ctx params]
  (retry/throttle
   #(mc/response-for ctx :create-email params)
   {:retries 5}))

(defn- apply-rcpt
  "If `b` is a function, invokes it with `rcpt`, otherwise just returns `b`.
   This is intended to allow invokers incorporate the recipient into the
   mail, e.g. for a title or unsubscribe link."
  [b rcpt]
  (if (ifn? b)
    (b rcpt)
    b))

(defrecord ScwMailer [config]
  co/Lifecycle
  (start [this]
    (assoc this :ctx (scw/email-ctx config)))

  (stop [this]
    (dissoc this :ctx))
  
  p/Mailer
  (send-mail [this {:keys [subject text-body html-body destinations]}]
    (let [uh (:unsubscribe config)]
      (-> (for [rcpt destinations]
            (->> (select-keys config [:project-id :from :bcc])
                 (merge (cond-> (->> [subject text-body html-body]
                                     (map #(apply-rcpt % rcpt))
                                     (zipmap [:subject :text :html])
                                     (merge 
                                      {:to (map (partial hash-map :email) [rcpt])}))
                          uh (assoc :additional-headers
                                    [{:key "list-unsubscribe"
                                      :value (format uh rcpt)}])))
                 (m/filter-vals some?)
                 (hash-map :region (:region config) :body)
                 (send-with-retry (:ctx this))
                 (deref)
                 :body))
          (doall)))))
