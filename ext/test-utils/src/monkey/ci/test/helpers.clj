(ns monkey.ci.test.helpers
  "Helper functions for testing"
  (:require [aleph.netty :as an]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [manifold.deferred :as md]
            [monkey.ci
             [cuid :as cuid]
             [protocols :as p]])
  (:import (org.apache.commons.io FileUtils)))

(defn ^java.io.File create-tmp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir") (str "tmp-" (random-uuid)))
    (.mkdirs)))

(defn with-tmp-dir-fn
  "Creates a temp dir and passes it to `f`.  Recursively deletes the temp dir afterwards."
  [f]
  (let [tmp (create-tmp-dir)]
    (try
      (f (.getAbsolutePath tmp))
      (finally
        (FileUtils/deleteDirectory tmp)))))

(defmacro with-tmp-dir [dir & body]
  `(with-tmp-dir-fn
     (fn [d#]
       (let [~dir d#]
         ~@body))))

(defn wait-until [f timeout]
  (let [end (+ (System/currentTimeMillis) timeout)]
    (loop [r (f)]
      (if r
        r
        (if (> (System/currentTimeMillis) end)
          :timeout
          (do
            (Thread/sleep 100)
            (recur (f))))))))

(defrecord FakeBlobStore [stored strict?]
  p/BlobStore  
  (save-blob [_ src dest md]
    (md/success-deferred (swap! stored assoc dest src)))

  (restore-blob [_ src dest]
    (if (or (not strict?)
            (= dest (get @stored src)))
      (md/success-deferred
       (do
         (swap! stored dissoc src)
         {:src src
          :dest dest
          :entries []}))
      (md/error-deferred (ex-info
                          (format "destination path was not as expected: %s, actual: %s" (get @stored src) dest)
                          @stored))))
  
  (get-blob-stream [_ src]
    (md/success-deferred
     (when (contains? @stored src)
       (io/input-stream (.getBytes "This is a test stream")))))

  (put-blob-stream [this src dest]
    (p/save-blob this src dest nil))

  (get-blob-info [_ _]
    nil))

(defn fake-blob-store
  ([stored]
   (->FakeBlobStore stored false))
  ([]
   (fake-blob-store (atom {}))))

(defn strict-fake-blob-store [stored]
  (->FakeBlobStore stored true))

(defn generate-private-key []
  (-> (doto (java.security.KeyPairGenerator/getInstance "RSA")
        (.initialize 2048))
      (.generateKeyPair)
      (.getPrivate)))

(defn first-event-by-type [type events]
  (->> events
       (filter (comp (partial = type) :type))
       (first)))

(defrecord FakeServer [closed?]
  java.lang.AutoCloseable
  (close [_]
    (reset! closed? true))
  an/AlephServer
  (port [_]
    0))

(defn base64->
  "Converts from base64"
  [x]
  (when x
    (String.
     (.. (java.util.Base64/getDecoder)
         (decode x)))))

(defrecord FakeMailer [mailings]
  p/Mailer
  (send-mail [_ mail]
    (swap! mailings conj mail)
    (md/success-deferred
     {:type :fake
      :id (str (random-uuid))
      :mail mail})))

(defn fake-mailer []
  (->FakeMailer (atom [])))

(defn gen-build-sid []
  (repeatedly 3 cuid/random-cuid))
