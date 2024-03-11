(ns blob
  (:require [clojure.java.io :as io]
            [monkey.ci.blob :as blob]))

(defn- used-memory
  "Returns used memory in gbs"
  []
  (let [rt (Runtime/getRuntime)]
    (-> (- (.totalMemory rt) (.freeMemory rt))
        (/ (* 1024 1024 1024))
        (float))))

(defn compression-test [src dest]
  (let [destf (io/file dest)]
    (printf "Memory used: %.2f GB\n" (used-memory))
    (println "Archiving" src "into" dest "...")
    (.delete destf)
    (blob/make-archive (io/file src) destf)
    (printf "Done archiving.  Resulting size is %.2f MB.  Memory used is now: %.2f GB\n"
            (-> destf (.length) (/ (* 1024 1024)) (float))
            (used-memory))))
