(ns agg.ui.project
  (:require [agg.ui.wizard :as wizard]))

(def schema-version 1)

(def ^:private route-order
  [:transparent-overlay :finished-video])

(def ^:private overlay-order
  [:timer :spo2 :watermark])

(def ^:private project-fields
  #{:schemaVersion
    :activeRoute
    :currentStepId
    :visitedStepIds
    :sharedInput
    :decisions
    :routeDrafts
    :optionalOverlayDrafts
    :renderRequest})

(def ^:private request-fields
  #{:telemetryFormat
    :telemetry
    :preset
    :displayTimeZone
    :futureTraceOpacityPercent
    :synchronizationMode
    :telemetrySyncAt
    :cameraSyncAt
    :sectionStartAt
    :sectionEndAt
    :spo2
    :timer
    :watermark
    :sourceVideo
    :outputFormat
    :fitMode
    :audioMode})

(def ^:private iso-pattern
  #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$")

(defn- route-name [route]
  (when route
    (name route)))

(defn- route-key [route]
  (keyword route))

(defn- ordered-visited-steps [state]
  (let [visited (:visited-steps state)
        active (wizard/active-steps state)
        ordered (vec (filter visited active))]
    (if (and (:current-step state)
             (visited (:current-step state))
             (not (some #{(:current-step state)} ordered)))
      (conj ordered (:current-step state))
      ordered)))

(defn export-project [state]
  (let [render-request (wizard/submit-ready-request state)]
    {:schemaVersion schema-version
     :activeRoute (route-name (:active-route state))
     :currentStepId (some-> (:current-step state) name)
     :visitedStepIds (mapv name (ordered-visited-steps state))
     :sharedInput (:shared-input state)
     :decisions {:synchronizationMode (some-> (get-in state [:decisions :synchronization-mode]) name)
                 :optionalOverlays (->> overlay-order
                                        (filter (get-in state [:decisions :optional-overlays]))
                                        (mapv name))}
     :routeDrafts (into {}
                        (map (fn [route]
                               [route
                                (get-in state [:route-drafts route] {})]))
                        route-order)
     :optionalOverlayDrafts (into {}
                                  (map (fn [overlay]
                                         [overlay
                                          (get-in state [:optional-overlay-drafts overlay] {})]))
                                  overlay-order)
     :renderRequest render-request}))

(defn- add-error [errors message]
  (conj errors message))

(defn- unknown-field-errors [candidate allowed path]
  (reduce (fn [errors key]
            (if (contains? allowed key)
              errors
              (add-error errors
                         (str path " contains unknown field " (name key) "."))))
          []
          (keys candidate)))

(defn- object? [value]
  (and (map? value)
       (not (record? value))))

(defn- string-present? [value]
  (and (string? value)
       (not= "" value)))

(defn- instant? [value]
  (and (string-present? value)
       (re-matches iso-pattern value)
       (try
         (java.time.Instant/parse value)
         true
         (catch Exception _
           false))))

(defn- validate-request [request path]
  (let [errors (if-not (object? request)
                 [(str path " must be a JSON object.")]
                 (unknown-field-errors request request-fields path))]
    (cond-> errors
      (and (contains? request :sectionStartAt)
           (not (instant? (:sectionStartAt request))))
      (add-error
       (str path ".sectionStartAt must be an ISO-8601 instant with Z or an explicit UTC offset.")))))

(defn validate-project [project]
  (let [errors (if-not (object? project)
                 ["Project must be a JSON object."]
                 (unknown-field-errors project project-fields "Project"))
        errors (if (= schema-version (:schemaVersion project))
                 errors
                 (add-error errors
                            (str "Project.schemaVersion must be "
                                 schema-version ".")))
        errors (cond-> errors
                 (contains? project :renderRequest)
                 (into (validate-request (:renderRequest project)
                                         "Project.renderRequest")))]
    errors))
