(ns monkey.ci.script.build
  "Build utility functions")

(def sid-props [:org-id :repo-id :build-id])

(def account->sid (juxt :org-id :repo-id))
(def props->sid
  "Constructs sid from build properties"
  (apply juxt sid-props))

(def sid
  "Gets the sid from the build"
  (some-fn :sid props->sid))

(defn status [v]
  {:status v})

(def success (status :success))
(def failure (status :failure))
(def skipped (status :skipped))

(defn with-message [r msg]
  (assoc r :message msg))

(defn add-warning [r w]
  (update r :warnings (fnil conj []) w))

(def warnings
  "Gets result warnings"
  :warnings)

(defn status?
  "Checks if the given object is a job status"
  [x]
  (some? (:status x)))

(defn success? [{:keys [status]}]
  (or (nil? status)
      (= :success status)))

(defn failed? [{:keys [status]}]
  (#{:failure :error} status))

(defn skipped? [{:keys [status]}]
  (= :skipped status))

(def script "Gets script from the build"
  :script)

(def script-dir
  "Gets script dir from the build"
  (comp :script-dir script))

(defn set-script-dir [b d]
  (assoc-in b [:script :script-dir] d))

(def checkout-dir
  "Gets the checkout dir as stored in the build structure"
  :checkout-dir)

(defn set-checkout-dir [b d]
  (assoc b :checkout-dir d))
