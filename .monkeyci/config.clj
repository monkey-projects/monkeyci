(ns config
  "Configuration settings"
  (:require [monkey.ci.api :as m]
            [predicates :as p]))

(defn tag-version
  "Extracts the version from the tag"
  [ctx]
  (some->> (m/git-ref ctx)
           (re-matches p/tag-regex)
           (second)))

(defn image-version
  "Retrieves image version from the tag, or the build id if this is the main branch."
  [ctx]
  ;; Prefix prod images with "release" for image retention policies
  (or (some->> (tag-version ctx) (str "release-"))
      (m/build-id ctx)
      ;; Fallback
      "latest"))

(def img-base "rg.fr-par.scw.cloud/monkeyci")
(def app-img (str img-base "/monkeyci-api"))
(def gui-img (str img-base "/monkeyci-gui"))

(defn app-image [ctx]
  (str app-img ":" (image-version ctx)))

(defn archs [_]
  ;; Use fallback for safety
  #_(or (m/archs ctx) [:amd])
  ;; Using single arch for now.  When using a container agent, it may happen that
  ;; multiple builds run on the same agent but for different architectures, which may
  ;; mess up the result (e.g. amd container but actually arm arch)
  [:amd])
