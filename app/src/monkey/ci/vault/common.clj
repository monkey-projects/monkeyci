(ns monkey.ci.vault.common
  (:require [buddy.core
             [codecs :as codecs]
             [crypto :as bcc]
             [nonce :as bcn]]
            [monkey.ci.vault :as v]))

;; Key size determines algorithm and iv length
(def algo {:algo :aes-256-gcm})

(defn generate-key
  "Generates random encryption key"
  []
  (bcn/random-nonce v/dek-size))

(defn generate-iv
  "Generates a random initialization vector for AES encryption"
  []
  (bcn/random-nonce v/iv-size))

(defn encrypt
  "Performs AES encryption of the given text"
  [enc-key iv txt]
  (-> (bcc/encrypt (codecs/str->bytes txt)
                   enc-key
                   iv
                   algo)
      (codecs/bytes->b64-str)))

(defn decrypt
  "Performs AES decryption of the given encrypted value"
  [enc-key iv enc]
  (-> (bcc/decrypt (codecs/b64->bytes enc)
                   enc-key
                   iv
                   algo)
      (codecs/bytes->str)))
