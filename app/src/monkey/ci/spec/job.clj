(ns monkey.ci.spec.job
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.common :as c]))

(s/def :job/id c/id?)

(s/def ::spec (s/keys :req [:job/id]
                      :opt [:job/action :job/container
                            :job/dependencies :job/caches
                            :job/save-artifacts :job/restore-artifacts]))
(defn- single-kind?
  "Verifies that only a single job kind key is present"
  [s]
  (= 1 (count (clojure.set/intersection (set (keys s)) #{:action :container}))))

;;; Action jobs

(s/def ::action
  (-> (s/keys :req [:job/action])
      (s/merge ::spec)))
(s/def :job/action fn?)

;;; Container jobs

(s/def ::container
  (-> (s/keys :req [:job/container])
      (s/merge ::spec)))
(s/def :job/container (s/keys :req [:container/image :container/commands]
                              :opt [:container/memory]))
(s/def :container/image string?)
(s/def :container/memory int?)
(s/def :container/command string?)
(s/def :container/commands (s/coll-of :container/command))

(s/def :job/props (s/or :action ::action
                        :container ::container))

(s/def :job/dependencies (s/coll-of :job/id))

;;; Blob objects

(s/def :blob/id c/id?)
(s/def :blob/path c/path?)
(s/def :blob/size int?)
(s/def :blob/props
  (s/keys :req [:blob/id :blob/path]
          :opt [:blob/size]))

(def blobs (s/coll-of :blob/props))

(s/def :job/caches blobs)
(s/def :job/save-artifacts blobs)
(s/def :job/restore-artifacts blobs)

;;; Job status: changeable

(s/def :job/status (-> (s/keys :req [:job/id :job/phase]
                               :opt [:job/result])
                       (s/merge :entity/timed)))

;; Job lifecycle phases
(s/def :job/phase #{:pending :starting :running :stopping :completed :skipped})
(s/def :job/result (s/keys :opt [:job/success :job/output :job/warnings :job/exit-code]))
(s/def :job/success boolean?)

(s/def :job/states (s/coll-of :job/status))
