(ns monkey.ci.vault
  "Functions related to encryption/decryption of data using a vault"
  (:require [buddy.core.nonce :as bcn]
            [monkey.ci.protocols :as p]
            [monkey.ci.vault
             [common :as vc]
             [oci :as vo]]))

(def iv-size 16)

;; Fixed key vault, that uses a preconfigured key.  Useful for testing or developing.
(defrecord FixedKeyVault [encryption-key]
  p/Vault
  (encrypt [_ iv txt]
    (vc/encrypt encryption-key iv txt))
  
  (decrypt [_ iv enc]
    (vc/decrypt encryption-key iv enc)))

(defn generate-key
  "Generates random encryption key"
  []
  (bcn/random-nonce 32))

(defn make-fixed-key-vault [config]
  (->FixedKeyVault (or (:encryption-key config) (generate-key))))

(defmulti make-vault :type)

(defmethod make-vault :oci [config]
  (vo/make-oci-vault config))

(defmethod make-vault :fixed [config]
  (make-fixed-key-vault config))

(defn generate-iv
  "Generates a random initialization vector for AES encryption"
  []
  (bcn/random-nonce iv-size))
