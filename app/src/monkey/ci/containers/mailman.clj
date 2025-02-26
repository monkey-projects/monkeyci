(ns monkey.ci.containers.mailman
  "A container runner that just posts an event to a mailman broker, expecting that
   the configured routes will execute the job."
  (:require [monkey.ci
             [jobs :as j]
             [protocols :as p]]
            [monkey.ci.events.mailman :as em]))

(defrecord MailmanContainerRunner [mailman build]
  p/ContainerRunner
  (run-container [this job]
    (em/post-events mailman [(j/job-pending-evt job (:sid build))])))

(defn make-container-runner []
  (map->MailmanContainerRunner {}))
