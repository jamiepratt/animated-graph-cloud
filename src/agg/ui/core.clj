(ns agg.ui.core
  (:require [agg.admin.core :as admin]
            [clojure.string :as str]))

(defn escape-html [value]
  (-> (str (or value ""))
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#x27;")))

(defn- title-case [value]
  (-> value
      (str/replace "-" " ")
      str/capitalize))

(defn token-panel
  ([tokens]
   (token-panel tokens nil))
  ([tokens created]
   (str
    "<section id=\"tokens\"><h2>Personal API tokens</h2>"
    (when created
      (str "<div class=\"notice\"><p>Copy this token now. It will not be shown again.</p>"
           "<code>" (escape-html (:token created)) "</code></div>"))
    "<form hx-post=\"/ui/tokens\" hx-target=\"#tokens\" hx-swap=\"outerHTML\">"
    "<label>Token name <input name=\"name\" maxlength=\"80\" required></label>"
    "<button type=\"submit\">Create token</button></form><ul>"
    (apply str
           (for [{:keys [id name createdAt revoked]} tokens]
             (str "<li><strong>" (escape-html name) "</strong> · "
                  (escape-html createdAt)
                  (if revoked
                    " · Revoked"
                    (str " <button type=\"button\" hx-post=\"/ui/tokens/"
                         (escape-html id)
                         "/revoke\" hx-target=\"#tokens\" hx-swap=\"outerHTML\">"
                         "Revoke</button>"))
                  "</li>")))
    "</ul></section>")))

(defn member-panel [members]
  (str
   "<section id=\"members\"><h2>Member administration</h2>"
   "<form hx-post=\"/ui/admin/members\" hx-target=\"#members\" hx-swap=\"outerHTML\">"
   "<label>Member email <input type=\"email\" name=\"email\" maxlength=\"254\" required></label>"
   "<button type=\"submit\">Add member</button></form><ul>"
   (apply str
          (for [{:keys [email role status]} members]
            (str "<li><strong>" (escape-html email) "</strong> · "
                 (escape-html role) " · " (escape-html status)
                 (when (and (= "member" role) (= "active" status))
                   (str " <form class=\"inline\" hx-post=\"/ui/admin/members/revoke\" "
                        "hx-target=\"#members\" hx-swap=\"outerHTML\">"
                        "<input type=\"hidden\" name=\"email\" value=\""
                        (escape-html email) "\"><button type=\"submit\">Revoke</button></form>"))
                 "</li>")))
   "</ul></section>"))

(defn job-fragment [{:keys [id state attempt failureCode output]}]
  (let [path (str "/ui/jobs/" id)
        polling? (contains? #{"queued" "launching" "running"
                              "cancellation-requested"}
                            state)
        cancellable? (contains? #{"queued" "launching" "running"} state)
        retryable? (contains? #{"failed" "cancelled"} state)
        drive-link (:driveWebViewLink output)]
    (str "<article id=\"job-" (escape-html id) "\" class=\"job\""
         (when polling?
           (str " hx-get=\"" (escape-html path)
                "\" hx-trigger=\"load delay:2s\" hx-swap=\"outerHTML\""))
         "><h2>" (escape-html (title-case state))
         "</h2><p>Attempt " (long attempt) "</p>"
         (when failureCode
           (str "<p>Failure: " (escape-html failureCode) "</p>"))
         (when drive-link
           (str "<p><a href=\"" (escape-html drive-link)
                "\" rel=\"noopener noreferrer\">Open result in Google Drive</a></p>"))
         (when cancellable?
           (str "<button type=\"button\" hx-post=\"" (escape-html path)
                "/cancel\" hx-target=\"#job-" (escape-html id)
                "\" hx-swap=\"outerHTML\">Cancel</button>"))
         (when retryable?
           (str "<button type=\"button\" hx-post=\"" (escape-html path)
                "/retry\" hx-target=\"#job-" (escape-html id)
                "\" hx-swap=\"outerHTML\">Retry</button>"))
         "</article>")))

(defn preview-fragment [base64-png]
  (str "<figure id=\"preview-result\"><img alt=\"Midpoint preview\" src=\"data:image/png;base64,"
       base64-png "\"></figure>"))

(defn page [{:keys [user csrf tokens members]}]
  (let [csrf-headers (escape-html
                      (str "{\"X-CSRF-Token\":\"" csrf "\"}"))]
    (str
     "<!doctype html><html><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
     "<title>Alpha Compose</title>"
     "<script src=\"https://cdn.jsdelivr.net/npm/htmx.org@2.0.10/dist/htmx.min.js\" "
     "integrity=\"sha384-H5SrcfygHmAuTDZphMHqBJLc3FhssKjG7w/CeCpFReSfwBWDTKpkzPP8c+cLsK+V\" "
     "crossorigin=\"anonymous\"></script>"
     "<style>body{font-family:system-ui;max-width:70rem;margin:2rem auto;padding:0 1rem}"
     "textarea{width:100%;min-height:18rem}section,article{margin:1.5rem 0}"
     "button{margin:.4rem}.inline{display:inline}.notice{border:2px solid #a66;padding:1rem;overflow-wrap:anywhere}</style>"
     "</head><body hx-headers=\"" csrf-headers "\">"
     "<header><h1>Alpha Compose</h1><p>Signed in as "
     (escape-html (:email user)) "</p></header>"
     "<section><h2>Drive inputs</h2><p><a href=\"/v1/auth/drive/start\">Connect Google Drive</a></p>"
     "<button id=\"open-picker\" type=\"button\">Select telemetry or watermark</button>"
     "<p>Selected: <output id=\"picker-selection\">None</output></p></section>"
     "<section><h2>Render</h2><form id=\"render-form\" hx-post=\"/ui/jobs\" "
     "hx-target=\"#job-result\" hx-swap=\"innerHTML\">"
     "<label>Render request JSON<textarea name=\"request\" required>{}</textarea></label>"
     "<button type=\"button\" hx-post=\"/ui/preview\" hx-include=\"closest form\" "
     "hx-target=\"#preview-result\" hx-swap=\"outerHTML\">Preview</button>"
     "<button type=\"submit\">Submit render</button></form>"
     "<div id=\"preview-result\"></div><div id=\"job-result\"></div></section>"
     (token-panel tokens)
     (when (admin/administrator? (:role user))
       (member-panel members))
     "<footer><a href=\"/privacy\">Privacy</a> · <a href=\"/terms\">Terms</a></footer>"
     "<script>const selection=document.getElementById('picker-selection');"
     "document.getElementById('open-picker').addEventListener('click',()=>{"
     "window.open('/v1/drive/picker','agg-picker','popup,width=960,height=720');});"
     "window.addEventListener('message',event=>{"
     "if(event.origin!==location.origin||event.data?.type!=='agg-picker'){return;}"
     "selection.textContent=event.data.files.map(file=>file.name).join(', ')||'None';"
     "});</script></body></html>")))

(defn- public-page [title body]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>" title " · Alpha Compose</title>"
       "<style>body{font-family:system-ui;max-width:48rem;margin:3rem auto;padding:0 1.25rem;line-height:1.55}"
       "header,footer{margin:2rem 0}nav a{margin-right:1rem}.cta{display:inline-block;padding:.65rem 1rem;background:#17202a;color:white;text-decoration:none;border-radius:.3rem}"
       "h1,h2{line-height:1.2}</style></head><body>"
       "<header><nav><a href=\"/\">Alpha Compose</a><a href=\"/privacy\">Privacy</a>"
       "<a href=\"/terms\">Terms</a></nav></header><main>" body "</main>"
       "<footer><small>© 2026 Alpha Compose · <a href=\"mailto:me@jamiep.org\">Contact</a></small></footer>"
       "</body></html>"))

(def anonymous-page
  (public-page
   "Telemetry overlays for video"
   (str "<h1>Bring telemetry to your video</h1>"
        "<p>Alpha Compose turns heart-rate and other supported telemetry into "
        "transparent video overlays for short videos. Export the overlay, then "
        "place it above your footage in your editor.</p>"
        "<p>Alpha Compose does not upload or composite your source video.</p>"
        "<p>Google account information is requested to sign in approved users. "
        "Connecting Google Drive is optional and requests only the "
        "<code>drive.file</code> permission, limited to files you select or "
        "Alpha Compose creates for input selection and output delivery.</p>"
        "<p><a class=\"cta\" href=\"/v1/auth/login/start\">Sign in with Google</a></p>")))

(def privacy-page
  (public-page
   "Privacy policy"
   (str "<h1>Privacy policy</h1><p><strong>Effective 17 July 2026.</strong></p>"
        "<p>Questions or deletion "
        "requests may be sent to <a href=\"mailto:me@jamiep.org\">me@jamiep.org</a>.</p>"
        "<h2>Information used</h2><p>We use your Google account identifier and "
        "email address to authenticate you and enforce the administrator-managed access list. "
        "When you connect Google Drive, Alpha Compose receives only the "
        "<code>drive.file</code> permission, allowing access to files you select or "
        "that Alpha Compose creates. We process telemetry and optional watermark "
        "content solely to create the requested overlay.</p>"
        "<h2>Storage and retention</h2><p>Encrypted Google Drive authorization, "
        "membership, and job records are stored in Google Cloud. Temporary request "
        "and output objects are deleted after 24 hours; job metadata is scheduled for "
        "deletion after 90 days. Completed overlays are delivered to your Google Drive "
        "and remain there until you delete them.</p>"
        "<h2>Sharing and security</h2><p>We use Google Cloud and Google Drive to "
        "operate the service. We do not sell personal information or use telemetry for "
        "advertising. Access is limited to approved accounts; credentials are encrypted, "
        "and application logs exclude telemetry values, filenames, email addresses, and tokens. "
        "Use and transfer of information received from Google APIs follows the "
        "<a href=\"https://developers.google.com/terms/api-services-user-data-policy\">Google API Services User Data Policy</a>, "
        "including its Limited Use requirements.</p>"
        "<h2>Your choices</h2><p>You may disconnect Alpha Compose in your Google Account, "
        "delete delivered files from Drive, or contact us to request deletion of service records. "
        "Revoking Drive access may prevent pending renders from completing.</p>")))

(def terms-page
  (public-page
   "Terms of service"
   (str "<h1>Terms of service</h1><p><strong>Effective 17 July 2026.</strong></p>"
        "<p>By using Alpha Compose you agree to these terms. If you do not agree, do not use the service.</p>"
        "<h2>Permitted use</h2><p>You may use Alpha Compose only with content and telemetry "
        "you are entitled to process. Do not misuse the service, attempt to bypass its access "
        "controls or limits, or use it unlawfully.</p>"
        "<h2>Your content</h2><p>You retain ownership of your inputs and outputs. You grant "
        "the operator only the limited permission needed to process them and deliver your overlay.</p>"
        "<h2>Availability</h2><p>The service is provided as available, without a guarantee of "
        "uninterrupted operation or fitness for a particular purpose. Verify every output before "
        "publication or reliance on it. Telemetry overlays are not medical advice.</p>"
        "<h2>Liability and termination</h2><p>To the extent permitted by law, the operator is "
        "not liable for indirect or consequential loss. Access may be suspended for misuse, "
        "security, maintenance, or cost control. You may stop using the service at any time.</p>"
        "<h2>Contact</h2><p>Questions may be sent to "
        "<a href=\"mailto:me@jamiep.org\">me@jamiep.org</a>.</p>")))
