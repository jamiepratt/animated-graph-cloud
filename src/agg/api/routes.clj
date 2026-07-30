(ns agg.api.routes)

(def ^:private uuid
  "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")

(defn feature-for
  "Returns the bounded feature that owns a public route.

  This is intentionally transport-only: authentication and response shaping
  remain at the API boundary so every feature keeps the same external contract.
  "
  [{:keys [method path]}]
  (cond
    (= path "/health") :health
    (contains? #{"/openapi.yaml" "/alpha-compose-mark.svg" "/favicon.svg"
                 "/favicon-16.png" "/favicon-32.png" "/telemetry-background.webp"
                 "/apple-touch-icon.png" "/icon-192.png" "/icon-512.png"}
               path) :assets
    (or (re-matches #"/v1/auth/login/(?:start|callback)" path)
        (= path "/v1/auth/logout")) :auth
    (or (= path "/v1/early-access/request")
        (= path "/")
        (contains? #{"/faq" "/privacy" "/terms"} path)) :ui
    (or (re-matches #"/v1/(?:drive/(?:picker|picker/diagnostic|playback-sessions|playback-analyses|recording-clock-inspections)|ui/project-source-validation)" path)
        (re-matches (re-pattern (str "/v1/drive/playback/" uuid)) path)) :playback
    (or (= path "/v1/preview")
        (= path "/ui/preview")
        (re-matches (re-pattern (str "/v1/previews/" uuid "(?:/images/[A-Za-z0-9_-]{1,64}/(?:thumbnail|full))?")) path)
        (re-matches (re-pattern (str "/ui/previews/" uuid)) path)) :preview
    (or (= path "/v1/tokens")
        (= path "/ui/tokens")
        (re-matches #"/(?:v1|ui)/tokens/[^/]+/revoke" path)) :tokens
    (or (re-matches #"/(?:v1|ui)/admin/(?:members(?:/revoke)?|logs)" path)) :admin
    (or (= path "/v1/uploads")
        (= path "/v1/jobs")
        (= path "/v1/derivative-preparations")
        (= path "/ui/jobs")
        (re-matches
         (re-pattern
          (str "/v1/derivative-preparations/" uuid
               "(?:/(?:cancel|retry|playback-sessions|playback/" uuid "))?"))
         path)
        (= path "/internal/v1/derivative-preparations/reconcile")
        (re-matches
         (re-pattern
          (str "/internal/v1/derivative-preparations/" uuid
               "/attempts/[1-9][0-9]*/dispatch"))
         path)
        (re-matches #"/(?:v1|ui)/jobs/[^/]+(?:/(?:cancel|retry))?" path)
        (re-matches #"/internal/v1/jobs/(?:reconcile|[^/]+/dispatch)" path)) :jobs
    :else :not-found))
