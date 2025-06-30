(ns monkey.ci.vault.common
  (:require [buddy.core
             [codecs :as codecs]
             [crypto :as bcc]]))

;; Key size determines algorithm and iv length
(def algo {:algo :aes-256-gcm})

(defn encrypt [enc-key iv txt]
  (-> (bcc/encrypt (codecs/str->bytes txt)
                   enc-key
                   iv
                   algo)
      (codecs/bytes->b64-str)))

(defn decrypt [enc-key iv enc]
  (-> (bcc/decrypt (codecs/b64->bytes enc)
                   enc-key
                   iv
                   algo)
      (codecs/bytes->str)))
