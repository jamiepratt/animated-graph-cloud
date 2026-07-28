(ns agg.proto-source-test
  (:require [agg.auth.core :as auth]
            [agg.drive.core :as drive]
            [agg.proto.core :as proto]
            [clojure.test :refer [deftest is]]))

(defn- auth-system [gateway]
  (let [oauth (reify auth/OAuthClient
                (exchange-code! [_ _ _ _ _]
                  (throw (UnsupportedOperationException.))))
        grant-store (reify auth/GrantStore
                      (load-grant [_ _]
                        {:refresh-token-ciphertext "kms:refresh"
                         :folder-id "folder-1"})
                      (save-grant! [_ _ grant] grant)
                      (revoke-grant! [_ _] nil))
        cipher (reify auth/TokenCipher
                 (encrypt-token! [_ value] (str "kms:" value))
                 (decrypt-token! [_ value] (subs value 4)))
        token-client (reify auth/DriveTokenClient
                       (refresh-drive-token! [_ _]
                         {:access-token "drive-access"}))]
    (auth/system
     {:client-id "client-id"
      :client-secret "client-secret"
      :base-url "https://proto.alphacompose.test"
      :allowlist #{"owner@example.com"}
      :session-key (.getBytes "01234567890123456789012345678901")
      :oauth oauth
      :grant-store grant-store
      :cipher cipher
      :drive gateway
      :drive-token-client token-client})))

(deftest default-source-listing-falls-back-to-fixed-bootstrap
  (let [listed? (atom false)
        loaded (atom [])
        gateway
        (reify drive/FolderSourceListingGateway
          (list-folder-sources! [_ _ _]
            (reset! listed? true)
            (throw (ex-info "folder listing denied" {:type ::denied})))
          drive/SourceGateway
          (source-metadata! [_ _ file-id]
            (swap! loaded conj file-id)
            (case file-id
              "video-b" {:id "video-b"
                         :name "beta.mp4"
                         :mimeType "video/mp4"
                         :size 4096
                         :trashed false
                         :videoMediaMetadata {:durationMillis "61000"
                                              :width 1920
                                              :height 1080}}
              "video-a" {:id "video-a"
                         :name "alpha.mov"
                         :mimeType "video/quicktime"
                         :size 2048
                         :trashed false
                         :videoMediaMetadata {:durationMillis "42000"
                                              :width 1280
                                              :height 720}}))
          (stream-source! [_ _ _ _]
            (throw (UnsupportedOperationException.))))
        listing (proto/default-source-listing
                 (auth-system gateway)
                 "owner-subject"
                 proto/fixed-folder-id
                 ["video-b" "video-a"])]
    (is @listed?)
    (is (= ["video-b" "video-a"] @loaded))
    (is (= "fixed-bootstrap" (:listingMode listing)))
    (is (= proto/fixed-folder-id (:folderId listing)))
    (is (= [{:fileId "video-a"
             :fileName "alpha.mov"
             :mimeType "video/quicktime"
             :size 2048
             :durationSeconds 42.0
             :width 1280
             :height 720}
            {:fileId "video-b"
             :fileName "beta.mp4"
             :mimeType "video/mp4"
             :size 4096
             :durationSeconds 61.0
             :width 1920
             :height 1080}]
           (:sources listing)))))
