(ns monkey.ci.spec.job
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.common :as c]))

(s/def :script/job (s/keys :req-un [:job/id :job/spec]
                           :opt-un [:job/status]))

(s/def :job/id c/id?)

(s/def ::spec (s/keys :opt-un [:job/action :job/container
                               :job/dependencies :job/caches
                               :job/save-artifacts :job/restore-artifacts]))
(defn- single-kind?
  "Verifies that only a single job kind key is present"
  [s]
  (= 1 (count (clojure.set/intersection (set (keys s)) #{:action :container}))))

(s/def ::action
  (-> (s/keys :req-un [:job/action])
      (s/merge ::spec)))
(s/def :job/action fn?)

(s/def ::container
  (-> (s/keys :req-un [:job/container])
      (s/merge ::spec)))
(s/def :job/container (s/keys :req-un [:container/image]))
(s/def :container/image string?)

(s/def :job/spec (s/or :action ::action
                       :container ::container))

(s/def :job/dependencies (s/coll-of :job/id))

(s/def :blob/id c/id?)
(s/def :blob/path c/path?)
(s/def :blob/size int?)
(s/def ::blob
  (s/keys :req-un [:blob/id :blob/path]
          :opt-un [:blob/size]))

(s/def :job/caches (s/coll-of ::blob))
(s/def :job/save-artifacts (s/coll-of ::blob))
(s/def :job/restore-artifacts (s/coll-of ::blob))

(s/def :job/status (s/keys :req-un [:job/phase]
                           :opt-un [:job/result :job/success]))

;; Job lifecycle phases
(s/def :job/phase #{:pending :starting :running :stopping :completed :skipped})
(s/def :job/success boolean?)
(s/def :job/result (s/keys :opt-un [:job/output :job/warnings :job/exit]))
