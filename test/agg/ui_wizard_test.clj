(ns agg.ui-wizard-test
  (:require [agg.ui.wizard :as wizard]
            [clojure.test :refer [deftest is]]))

(def ^:private overlay-combinations
  [#{}
   #{:timer}
   #{:spo2}
   #{:watermark}
   #{:timer :spo2}
   #{:timer :watermark}
   #{:spo2 :watermark}
   #{:timer :spo2 :watermark}])

(deftest active-steps-follow-route-synchronization-and-overlay-decisions
  (doseq [route [:transparent-overlay :finished-video]
          synchronization-mode [:shared-clock :manual-anchor]
          overlays overlay-combinations]
    (let [state (-> (wizard/initial-state)
                    (wizard/choose-route route)
                    (wizard/choose-synchronization synchronization-mode)
                    (wizard/choose-optional-overlays overlays))
          steps (wizard/active-steps state)]
      (is (= :outcome (first steps)))
      (is (= :review (last steps)))
      (is (= (= route :finished-video)
             (boolean
              (and (some #{:source-video} steps)
                   (some #{:video-recording-clock} steps)))))
      (is (= (= route :transparent-overlay)
             (boolean (some #{:overlay-timespan} steps))))
      (is (= (= synchronization-mode :manual-anchor)
             (boolean (some #{:matching-moment} steps))))
      (doseq [[overlay step] [[:timer :timer-overlay]
                              [:spo2 :spo2-overlay]
                              [:watermark :watermark-overlay]]]
        (is (= (contains? overlays overlay)
               (boolean (some #{step} steps)))))
      (is (= (count steps) (count (distinct steps)))))))

(deftest dormant-route-and-overlay-drafts-survive-inactive-projection
  (let [finished-request
        {:sourceVideo {:fileId "drive-file"
                       :recordingStartAt "2026-07-17T10:00:00.000Z"
                       :timeZone "Europe/Warsaw"}
         :outputFormat "h264-mp4"
         :fitMode "letterbox"
         :audioMode "source+heartbeat"}
        transparent-request
        {:sectionStartAt "2026-07-17T09:00:00.000Z"
         :sectionEndAt "2026-07-17T09:00:01.000Z"}
        state (-> (wizard/initial-state)
                  (wizard/set-shared-input
                   {:telemetryFormat "polar-csv"
                    :telemetry "timestamp,heart_rate"
                    :preset "1080p25"
                    :displayTimeZone "Europe/Warsaw"
                    :sourceVideo {:fileId "inactive-leak"}
                    :outputFormat "inactive-leak"
                    :spo2 {:telemetry "inactive-leak"}
                    :watermark {:contentBase64 "inactive-leak"}})
                  (wizard/set-route-draft :finished-video finished-request)
                  (wizard/set-route-draft :transparent-overlay
                                          transparent-request)
                  (wizard/set-overlay-draft
                   :timer
                   {:startAt "2026-07-17T10:00:00.000Z"
                    :endAt "2026-07-17T10:00:01.000Z"})
                  (wizard/choose-route :finished-video)
                  (wizard/choose-synchronization :shared-clock)
                  (wizard/choose-optional-overlays #{:timer}))
        active-request (wizard/project-render-request state)
        transparent (wizard/choose-route state :transparent-overlay)
        inactive-request (wizard/project-render-request transparent)
        restored (wizard/choose-route transparent :finished-video)]
    (is (= (:sourceVideo finished-request) (:sourceVideo active-request)))
    (is (= {:startAt "2026-07-17T10:00:00.000Z"
            :endAt "2026-07-17T10:00:01.000Z"}
           (:timer active-request)))
    (is (not (contains? active-request :spo2)))
    (is (not (contains? active-request :watermark)))
    (is (not (contains? inactive-request :sourceVideo)))
    (is (not (contains? inactive-request :outputFormat)))
    (is (= transparent-request
           (select-keys inactive-request
                        [:sectionStartAt :sectionEndAt])))
    (is (= finished-request
           (get-in restored [:route-drafts :finished-video])))
    (is (= (get-in state [:optional-overlay-drafts :timer])
           (get-in (wizard/choose-optional-overlays state #{})
                   [:optional-overlay-drafts :timer])))))

(deftest navigation-completion-and-invalidation-use-semantic-step-ids
  (let [chosen (wizard/choose-route (wizard/initial-state) :finished-video)
        at-source (wizard/go-to-step chosen :source-video)
        source-complete (wizard/complete-step at-source :source-video)
        at-clock (wizard/go-to-step source-complete :video-recording-clock)
        downstream-complete (-> at-clock
                                (wizard/complete-step :video-recording-clock)
                                (wizard/complete-step :activity-data)
                                (wizard/complete-step :synchronization))
        invalidated (wizard/invalidate-after downstream-complete
                                             :video-recording-clock)]
    (is (wizard/step-complete? chosen :outcome))
    (is (wizard/navigation-eligible? chosen :source-video))
    (is (not (wizard/navigation-eligible? chosen :activity-data)))
    (is (= :source-video (:current-step at-source)))
    (is (wizard/navigation-eligible? source-complete
                                     :video-recording-clock))
    (is (= #{:activity-data :synchronization}
           (get-in invalidated [:validation :invalidated-steps])))
    (is (not (wizard/step-complete? invalidated :activity-data)))
    (is (= :video-recording-clock (:current-step invalidated)))))

(deftest completion-requires-every-active-input-step-but-not-review
  (doseq [route [:transparent-overlay :finished-video]]
    (let [state (-> (wizard/initial-state)
                    (wizard/choose-route route)
                    (wizard/choose-synchronization :manual-anchor)
                    (wizard/choose-optional-overlays #{:timer :spo2 :watermark}))
          completed (reduce wizard/complete-step
                            state
                            (remove #{:review}
                                    (wizard/active-steps state)))]
      (is (not (wizard/complete? state)))
      (is (wizard/complete? completed))
      (is (= (wizard/project-render-request completed)
             (wizard/submit-ready-request completed))))))

(deftest canonical-state-is-memory-only-and-excludes-operation-state
  (let [state (wizard/initial-state)]
    (is (= :outcome (:current-step state)))
    (is (= #{:outcome} (:visited-steps state)))
    (is (nil? (:active-route state)))
    (doseq [forbidden [:oauth-token :csrf :preview :job :result
                       :playback-url :recording-clock-candidates]]
      (is (not (contains? state forbidden))))))
