(ns agg.derivative-lifecycle-test
  (:require [agg.derivative.lifecycle :as derivative]
            [clojure.test :refer [deftest is]])
  (:import (java.time Instant)))

(deftest production-preparation-cancellation-is-truthful-and-scoped
  (let [submitted-at (Instant/parse "2026-07-30T10:00:00Z")
        running-at (Instant/parse "2026-07-30T10:00:01Z")
        cancelling-at (Instant/parse "2026-07-30T10:00:02Z")
        cancelled-at (Instant/parse "2026-07-30T10:00:03Z")
        job-id "00000000-0000-0000-0000-000000000211"
        queued (derivative/transition
                nil {:type :submit :id job-id :now submitted-at})
        running (derivative/transition
                 queued {:type :dispatch :now running-at})
        cancelling (derivative/transition
                    running {:type :cancel :now cancelling-at})
        cancelled (derivative/transition
                   cancelling {:type :complete
                               :outcome :cancelled
                               :now cancelled-at})]
    (is (= {:id job-id
            :environment "production"
            :state :queued
            :attempt 1
            :profile-version "h264-aac-1080p25-v1"
            :created-at submitted-at
            :updated-at submitted-at
            :metadata-expires-at
            (Instant/parse "2026-07-31T10:00:00Z")}
           queued))
    (is (= :running (:state running)))
    (is (= :cancellation-requested (:state cancelling)))
    (is (= :cancelled (:state cancelled)))))

(deftest completed-assets-expire-privately-with-stable-public-links
  (let [now (Instant/parse "2026-07-30T10:00:00Z")
        completed-at (Instant/parse "2026-07-30T10:00:10Z")
        expires-at (Instant/parse "2026-07-31T10:00:10Z")
        job-id "00000000-0000-0000-0000-000000000211"
        asset-id "00000000-0000-0000-0000-000000000212"
        completed
        (-> (derivative/transition nil {:type :submit :id job-id :now now})
            (derivative/transition {:type :dispatch :now now})
            (derivative/transition {:type :complete
                                    :outcome :succeeded
                                    :asset-id asset-id
                                    :object-key
                                    "production/derivative-previews/v1/private.mp4"
                                    :now completed-at}))
        resource (derivative/public-resource completed)
        expired (derivative/transition
                 completed {:type :expire :now expires-at})]
    (is (= {:id job-id
            :state "succeeded"
            :attempt 1
            :profileVersion "h264-aac-1080p25-v1"
            :assetId asset-id
            :expiresAt "2026-07-31T10:00:10Z"
            :statusUrl (str "/v1/derivative-preparations/" job-id)
            :cancelUrl (str "/v1/derivative-preparations/" job-id "/cancel")
            :retryUrl (str "/v1/derivative-preparations/" job-id "/retry")}
           resource))
    (is (nil? (:object-key resource)))
    (is (= :expired (:state expired)))
    (is (nil? (:object-key expired)))))
