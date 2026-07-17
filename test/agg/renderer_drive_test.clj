(ns agg.renderer-drive-test
  (:require [agg.auth.core :as auth]
            [agg.drive.core :as drive]
            [agg.jobs.lifecycle :as jobs]
            [agg.jobs-test :as fixture]
            [agg.renderer.main :as renderer]
            [clojure.test :refer [deftest is]])
  (:import (java.nio.file Files OpenOption)))

(deftest cloud-render-delivers-the-local-mov-before-cleanup
  (let [delivered (atom [])
        delivery (reify drive/OutputDelivery
                   (deliver-output! [_ job-id subject path]
                     (swap! delivered conj {:job-id job-id
                                            :subject subject
                                            :bytes (Files/readString path)})
                     {:fileId "drive-output-1"
                      :webViewLink "https://drive.google.com/file/d/drive-output-1/view"}))
        render-cloud!
        (fn [{:keys [output-path]} _dependencies]
          (Files/writeString output-path "movie" (make-array OpenOption 0))
          {:output-bytes 5
           :sha256 (apply str (repeat 64 "a"))
           :objects {:media {:object "jobs/job-1/output.mov"}}})
        worker (renderer/->CloudRenderWorker "temporary-bucket" delivery
                                             render-cloud!)
        result (jobs/perform-render! worker "job-1"
                                     (assoc (fixture/render-request)
                                            :requesterSubject
                                            "google-subject-1"))]
    (is (= [{:job-id "job-1"
             :subject "google-subject-1"
             :bytes "movie"}]
           @delivered))
    (is (= "drive-output-1" (:driveFileId result)))
    (is (= "https://drive.google.com/file/d/drive-output-1/view"
           (:driveWebViewLink result)))
    (is (= "jobs/job-1/output.mov" (:object result)))))

(deftest drive-reauthorization-has-a-data-free-operational-event
  (let [revoked (ex-info "secret-token filename.mov"
                         {:type ::auth/drive-grant-required
                          :access-token "secret-token"})
        wrapped (ex-info "worker failed" {:type ::jobs/worker-failed} revoked)
        event (renderer/cloud-failure-event wrapped)]
    (is (= {:severity "ERROR"
            :component "renderer"
            :event "drive_reauthorization_required"
            :message "Google Drive reauthorization is required"}
           event))
    (is (not (re-find #"secret-token|filename" (pr-str event))))
    (is (= "cloud_render_failed"
           (:event (renderer/cloud-failure-event
                    (ex-info "other failure" {})))))))

(deftest a-durable-failure-cannot-be-logged-as-a-completed-cloud-render
  (is (= {:state "succeeded"}
         (renderer/require-cloud-success! {:state "succeeded"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"durable failure"
                        (renderer/require-cloud-success!
                         {:state "failed"
                          :failureCode "output_too_large"}))))
