(ns monkey.ci.common.builds)

(defn success? [{:keys [status]}]
  (or (nil? status)
      (= :success status)))

(defn failed? [{:keys [status]}]
  (#{:failure :error} status))

(defn finished? [{:keys [status]}]
  (some? (#{:success :canceled :failure :error} status)))
