(ns agg.ui-project-test
  (:require [agg.ui.project :as project]
            [agg.ui.wizard :as wizard]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- complete-transparent-state []
  (-> (wizard/initial-state)
      (wizard/choose-route :transparent-overlay)
      (wizard/choose-synchronization :shared-clock)
      (wizard/choose-optional-overlays #{:timer})
      (wizard/set-shared-input
       {:telemetryFormat "polar-csv"
        :telemetry "timestamp,heart_rate\n2026-07-17T09:00:00Z,120"
        :preset "1080p25"
        :displayTimeZone "Europe/Warsaw"
        :futureTraceOpacityPercent 25})
      (wizard/set-route-draft
       :transparent-overlay
       {:sectionStartAt "2026-07-17T09:00:00.000Z"
        :sectionEndAt "2026-07-17T09:00:02.000Z"})
      (wizard/set-overlay-draft
       :timer
       {:startAt "2026-07-17T09:00:00.400Z"
        :endAt "2026-07-17T09:00:01.600Z"})
      (assoc :current-step :review
             :visited-steps #{:outcome :activity-data :output-timespan
                              :optional-overlays
                              :timer-overlay :output-settings :review})
      (update-in [:completion :completed-steps]
                 into
                 #{:outcome :activity-data :output-timespan
                   :optional-overlays
                   :timer-overlay :output-settings})))

(deftest export-project-json-keeps-canonical-state-and-complete-request
  (let [state (complete-transparent-state)
        envelope (project/export-project state)]
    (is (= project/schema-version (:schemaVersion envelope)))
    (is (= "transparent-overlay" (:activeRoute envelope)))
    (is (= "review" (:currentStepId envelope)))
    (is (= ["outcome" "activity-data" "output-timespan"
            "optional-overlays"
            "timer-overlay" "output-settings" "review"]
           (:visitedStepIds envelope)))
    (is (= ["timer"] (get-in envelope [:decisions :optionalOverlays])))
    (is (= {:telemetryFormat "polar-csv"
            :telemetry "timestamp,heart_rate\n2026-07-17T09:00:00Z,120"
            :preset "1080p25"
            :displayTimeZone "Europe/Warsaw"
            :futureTraceOpacityPercent 25}
           (:sharedInput envelope)))
    (is (= {:sectionStartAt "2026-07-17T09:00:00.000Z"
            :sectionEndAt "2026-07-17T09:00:02.000Z"}
           (get-in envelope [:routeDrafts :transparent-overlay])))
    (is (= {:timer {:startAt "2026-07-17T09:00:00.400Z"
                    :endAt "2026-07-17T09:00:01.600Z"}
            :spo2 {}
            :watermark {}}
           (:optionalOverlayDrafts envelope)))
    (is (= {:telemetryFormat "polar-csv"
            :telemetry "timestamp,heart_rate\n2026-07-17T09:00:00Z,120"
            :preset "1080p25"
            :displayTimeZone "Europe/Warsaw"
            :futureTraceOpacityPercent 25
            :synchronizationMode "shared-clock"
            :sectionStartAt "2026-07-17T09:00:00.000Z"
            :sectionEndAt "2026-07-17T09:00:02.000Z"
            :timer {:startAt "2026-07-17T09:00:00.400Z"
                    :endAt "2026-07-17T09:00:01.600Z"}}
           (:renderRequest envelope)))
    (doseq [forbidden [:oauthToken :csrf :preview :job :result
                       :playbackUrl :inspectionCandidates]]
      (is (not (contains? envelope forbidden))))))

(deftest export-project-json-omits-render-request-when-incomplete
  (let [state (-> (wizard/initial-state)
                  (wizard/choose-route :finished-video)
                  (wizard/choose-synchronization :manual-anchor)
                  (wizard/set-shared-input {:telemetryFormat "polar-csv"
                                            :telemetry "timestamp,heart_rate"
                                            :preset "1080p25"
                                            :displayTimeZone "UTC"})
                  (wizard/set-route-draft :finished-video
                                          {:sourceVideo {:fileId "drive-file"}
                                           :outputFormat "h264-mp4"}))
        envelope (project/export-project state)]
    (is (nil? (:renderRequest envelope)))
    (is (= "finished-video" (:activeRoute envelope)))
    (is (= "outcome" (:currentStepId envelope)))))

(deftest validate-project-json-rejects-unknown-version-and-fields
  (testing "top-level validation"
    (is (= ["Project.schemaVersion must be 1."]
           (project/validate-project
            {:schemaVersion 2})))
    (is (= ["Project contains unknown field extra."]
           (project/validate-project
            {:schemaVersion 1
             :activeRoute "transparent-overlay"
             :currentStepId "outcome"
             :visitedStepIds ["outcome"]
             :sharedInput {}
             :decisions {:synchronizationMode nil
                         :optionalOverlays []}
             :routeDrafts {"transparent-overlay" {}
                           "finished-video" {}}
             :optionalOverlayDrafts {:timer {}
                                     :spo2 {}
                                     :watermark {}}
             :extra true}))))
  (testing "nested render request reuses public validation"
    (let [errors (project/validate-project
                  {:schemaVersion 1
                   :activeRoute "transparent-overlay"
                   :currentStepId "review"
                   :visitedStepIds ["outcome" "review"]
                   :sharedInput {:telemetryFormat "polar-csv"
                                 :telemetry "timestamp,heart_rate"
                                 :preset "1080p25"
                                 :displayTimeZone "UTC"}
                   :decisions {:synchronizationMode "shared-clock"
                               :optionalOverlays []}
                   :routeDrafts {"transparent-overlay"
                                 {:sectionStartAt "2026-07-17T09:00:00.000Z"
                                  :sectionEndAt "2026-07-17T09:00:02.000Z"}
                                 "finished-video" {}}
                   :optionalOverlayDrafts {:timer {}
                                           :spo2 {}
                                           :watermark {}}
                   :renderRequest {:telemetryFormat "polar-csv"
                                   :telemetry "timestamp,heart_rate"
                                   :preset "1080p25"
                                   :displayTimeZone "UTC"
                                   :synchronizationMode "shared-clock"
                                   :sectionStartAt "not-an-instant"
                                   :sectionEndAt "2026-07-17T09:00:02.000Z"
                                   :unexpected true}})]
      (is (some #{"Project.renderRequest contains unknown field unexpected."}
                errors))
      (is (some #{"Project.renderRequest.sectionStartAt must be an ISO-8601 instant with Z or an explicit UTC offset."}
                errors)))))

(deftest browser-project-runtime-owns-export-import-and-validation
  (let [script (project/browser-runtime-script)]
    (is (str/includes? script "function projectText()"))
    (is (str/includes? script "function validateProject("))
    (is (str/includes? script "function restoreProject("))
    (is (str/includes? script "function applyProjectText("))))
