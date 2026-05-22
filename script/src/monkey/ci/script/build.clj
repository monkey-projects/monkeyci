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

(def success
  {:status :success})

(def script "Gets script from the build"
  :script)

(def script-dir
  "Gets script dir from the build"
  (comp :script-dir script))

(defn set-script-dir [b d]
  (assoc-in b [:script :script-dir] d))
