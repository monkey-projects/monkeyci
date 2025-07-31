(ns monkey.ci.build.v2
  "A group of functions written on top of the more low-level build functions, meant
   to improve writing build scripts.  They should make the build scripts more intuitive,
   more readable.  And, dare I say it, prettier?

   The general intention is to provide functions for most purposes, without the user
   having to resort to using keywords and maps.

   This namespace is deprecated, in favor of `monkey.ci.api`."
  
  (:require [monkey.ci.api :as api]))

;; Import all functions from api, for compatibility.
(doseq [[a v] (ns-publics 'monkey.ci.api)]
  (intern *ns* a v))

(defn ^:deprecated cpus
  "Gets or sets requested cpu count for container jobs.  Deprecated, use
   `size` instead."
  ([job]
   (:cpus job))
  ([job n]
   (api/try-unwrap job assoc :cpus n)))

(defn ^:deprecated memory
  "Gets or sets requested memory for container jobs, in GBs.  Deprecated,
   use `size` instead."
  ([job]
   (:memory job))
  ([job n]
   (api/try-unwrap job assoc :memory n)))
