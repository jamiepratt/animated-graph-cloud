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
              (some #{:source-video} steps))))
      (is (not (some #{:video-recording-clock} steps)))
      (is (= (= route :transparent-overlay)
             (boolean (some #{:overlay-timespan} steps))))
      (is (= (and (= route :transparent-overlay)
                  (= synchronization-mode :manual-anchor))
             (boolean (some #{:matching-moment} steps))))
      (doseq [[overlay step] [[:timer :timer-overlay]
                              [:spo2 :spo2-overlay]
                              [:watermark :watermark-overlay]]]
        (is (= (contains? overlays overlay)
               (boolean (some #{step} steps)))))
      (is (= (count steps) (count (distinct steps)))))))

(deftest manual-video-sync-derives-renderer-time-without-camera-clock-input
  (let [primary {:source-seconds 3600.04
                 :activity-instant "2026-10-25T01:30:00.000Z"
                 :time-zone "Europe/Warsaw"}
        derived (wizard/derive-manual-sync primary)]
    (is (= {:source-seconds 3600.04
            :activity-instant "2026-10-25T01:30:00.000Z"
            :time-zone "Europe/Warsaw"
            :recording-start-at "2026-10-25T00:29:59.960Z"
            :telemetry-sync-at "2026-10-25T01:30:00.000Z"
            :camera-sync-at "2026-10-25T01:30:00.000Z"}
           derived))
    (doseq [invalid [(assoc primary :source-seconds -0.04)
                     (assoc primary :source-seconds 1.01)
                     (assoc primary :activity-instant "not-an-instant")
                     (assoc primary :time-zone "+02:00")]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (wizard/derive-manual-sync invalid))))))

(deftest manual-video-sync-projects-only-stable-renderer-fields
  (let [state (-> (wizard/initial-state)
                  (wizard/set-shared-input
                   {:telemetryFormat "polar-csv"
                    :telemetry "timestamp,heart_rate"
                    :preset "1080p25"
                    :displayTimeZone "Europe/Warsaw"})
                  (wizard/set-route-draft
                   :finished-video
                   {:sourceVideo {:fileId "drive-file"}
                    :manualSync
                    {:sourceSeconds 10.04
                     :activityInstant "2026-07-17T10:00:00.000Z"
                     :timeZone "Europe/Warsaw"}
                    :sectionStartAt "2026-07-17T10:00:00.000Z"
                    :sectionEndAt "2026-07-17T10:00:02.000Z"
                    :outputFormat "h264-mp4"
                    :fitMode "letterbox"
                    :audioMode "source+heartbeat"})
                  (wizard/choose-route :finished-video)
                  (wizard/choose-synchronization :manual-anchor))
        request (wizard/project-render-request state)]
    (is (= {:fileId "drive-file"
            :recordingStartAt "2026-07-17T09:59:49.960Z"
            :timeZone "Europe/Warsaw"}
           (:sourceVideo request)))
    (is (= "2026-07-17T10:00:00.000Z"
           (:telemetrySyncAt request)))
    (is (= "2026-07-17T10:00:00.000Z"
           (:cameraSyncAt request)))
    (is (not (contains? request :manualSync)))))

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
                  (wizard/set-overlay-draft
                   :spo2
                   {:format "oxiwear-spo2-csv"
                    :telemetry "reading_time,spo2"})
                  (wizard/set-overlay-draft
                   :watermark
                   {:contentBase64 "iVBORw0KGgo="})
                  (wizard/choose-route :finished-video)
                  (wizard/choose-synchronization :shared-clock)
                  (wizard/choose-optional-overlays
                   #{:timer :spo2 :watermark}))
        active-request (wizard/project-render-request state)
        transparent (wizard/choose-route state :transparent-overlay)
        inactive-request (wizard/project-render-request transparent)
        deselected (wizard/choose-optional-overlays state #{})
        restored (-> deselected
                     (wizard/choose-route :transparent-overlay)
                     (wizard/choose-route :finished-video)
                     (wizard/choose-optional-overlays
                      #{:timer :spo2 :watermark}))
        restored-request (wizard/project-render-request restored)]
    (is (= (:sourceVideo finished-request) (:sourceVideo active-request)))
    (is (= {:startAt "2026-07-17T10:00:00.000Z"
            :endAt "2026-07-17T10:00:01.000Z"}
           (:timer active-request)))
    (is (= {:format "oxiwear-spo2-csv"
            :telemetry "reading_time,spo2"}
           (:spo2 active-request)))
    (is (= {:contentBase64 "iVBORw0KGgo="}
           (:watermark active-request)))
    (is (not (contains? inactive-request :sourceVideo)))
    (is (not (contains? inactive-request :outputFormat)))
    (is (not (contains? (wizard/project-render-request deselected)
                        :timer)))
    (is (not (contains? (wizard/project-render-request deselected)
                        :spo2)))
    (is (not (contains? (wizard/project-render-request deselected)
                        :watermark)))
    (is (= transparent-request
           (select-keys inactive-request
                        [:sectionStartAt :sectionEndAt])))
    (is (= finished-request
           (get-in restored [:route-drafts :finished-video])))
    (doseq [overlay [:timer :spo2 :watermark]]
      (is (= (get-in state [:optional-overlay-drafts overlay])
             (get-in deselected [:optional-overlay-drafts overlay])))
      (is (= (get-in state [:optional-overlay-drafts overlay])
             (get-in restored [:optional-overlay-drafts overlay])))
      (is (= (get-in state [:optional-overlay-drafts overlay])
             (get restored-request overlay))))))

(deftest navigation-completion-and-invalidation-use-semantic-step-ids
  (let [chosen (wizard/choose-route (wizard/initial-state) :finished-video)
        at-source (wizard/go-to-step chosen :source-video)
        source-complete (wizard/complete-step at-source :source-video)
        at-activity (wizard/go-to-step source-complete :activity-data)
        downstream-complete (-> at-activity
                                (wizard/complete-step :activity-data)
                                (wizard/complete-step :synchronization))
        invalidated (wizard/invalidate-after downstream-complete
                                             :activity-data)]
    (is (wizard/step-complete? chosen :outcome))
    (is (wizard/navigation-eligible? chosen :source-video))
    (is (not (wizard/navigation-eligible? chosen :activity-data)))
    (is (= :source-video (:current-step at-source)))
    (is (wizard/navigation-eligible? source-complete
                                     :activity-data))
    (is (= #{:synchronization}
           (get-in invalidated [:validation :invalidated-steps])))
    (is (not (wizard/step-complete? invalidated :synchronization)))
    (is (= :activity-data (:current-step invalidated)))))

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
