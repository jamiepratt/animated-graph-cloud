(ns agg.derivative-storage-test
  (:require [agg.derivative.storage :as storage]
            [clojure.test :refer [deftest is]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def publication
  {:job-id "00000000-0000-0000-0000-000000000195"
   :attempt 1
   :asset-id "00000000-0000-0000-0000-000000000195"
   :object-key
   "production/derivative-previews/v1/0000000000000000000000000000000000000000000000000000000000000195.mp4"
   :content-type "video/mp4"
   :size 10
   :profile-version "h264-aac-1080p25-v1"})

(deftest verified-publication-is-immutable-idempotent-and-generation-bound
  (let [store (storage/in-memory-asset-store)
        path (Files/createTempFile
              "derivative-storage-" ".mp4"
              (make-array FileAttribute 0))]
    (try
      (Files/write path (.getBytes "0123456789")
                   (make-array java.nio.file.OpenOption 0))
      (let [first-published
            (storage/publish-verified!
             store (assoc publication :output-path path))
            duplicate
            (storage/publish-verified!
             store (assoc publication :output-path path))
            opened
            (storage/open-range!
             store
             (merge {:object-key (:object-key publication)}
                    first-published)
             {:start 3 :end 7})]
        (is (= first-published duplicate))
        (is (= {:generation 1
                :size 10
                :content-type "video/mp4"
                :profile-version "h264-aac-1080p25-v1"}
               first-published))
        (is (= 206 (:status opened)))
        (is (= {"content-range" "bytes 3-7/10"
                "content-length" "5"}
               (:headers opened)))
        (is (= "34567"
               (with-open [body (:body opened)]
                 (String. (.readAllBytes body)))))
        (is (false?
             (storage/delete-generation!
              store {:object-key (:object-key publication)
                     :generation 2})))
        (is (true?
             (storage/delete-generation!
              store {:object-key (:object-key publication)
                     :generation 1})))
        (is (= ::storage/asset-unavailable
               (:type
                (try
                  (storage/open-range!
                   store
                   (merge {:object-key (:object-key publication)}
                          first-published)
                   {:start 0 :end 1})
                  nil
                  (catch clojure.lang.ExceptionInfo error
                    (ex-data error)))))))
      (finally
        (Files/deleteIfExists path)))))

(deftest publication-rejects-partial-or-conflicting-output
  (let [store (storage/in-memory-asset-store)
        path (Files/createTempFile
              "derivative-storage-invalid-" ".mp4"
              (make-array FileAttribute 0))]
    (try
      (Files/write path (.getBytes "short")
                   (make-array java.nio.file.OpenOption 0))
      (is (= ::storage/invalid-publication
             (:type
              (try
                (storage/publish-verified!
                 store (assoc publication :output-path path))
                nil
                (catch clojure.lang.ExceptionInfo error
                  (ex-data error))))))
      (Files/write path (.getBytes "0123456789")
                   (make-array java.nio.file.OpenOption 0))
      (storage/publish-verified! store (assoc publication :output-path path))
      (Files/write path (.getBytes "abcdefghij")
                   (make-array java.nio.file.OpenOption 0))
      (is (= ::storage/publication-conflict
             (:type
              (try
                (storage/publish-verified!
                 store (assoc publication :output-path path))
                nil
                (catch clojure.lang.ExceptionInfo error
                  (ex-data error))))))
      (finally
        (Files/deleteIfExists path)))))

(deftest playback-refuses-temporary-or-nonproduction-object-paths
  (let [bytes (.getBytes "0123456789")
        temporary-key "temporary/private-job/attempt-1/output.mp4"
        store
        (storage/->InMemoryAssetStore
         (atom
          {:next-generation 2
           :objects
           {temporary-key
            {:generation 1
             :size 10
             :content-type "video/mp4"
             :profile-version "h264-aac-1080p25-v1"
             :bytes bytes}}}))
        error-data
        (try
          (storage/open-range!
           store
           {:object-key temporary-key
            :generation 1
            :size 10
            :content-type "video/mp4"
            :profile-version "h264-aac-1080p25-v1"}
           {:start 0 :end 4})
          nil
          (catch clojure.lang.ExceptionInfo error
            (ex-data error)))]
    (is (= ::storage/asset-unavailable (:type error-data)))
    (is (not (contains? error-data :object-key)))))
