(ns build
  (:require [monkey.ci.api :as m]))

(m/action-job "failing-job" (constantly m/failure))


