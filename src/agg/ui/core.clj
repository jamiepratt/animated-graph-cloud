(ns agg.ui.core
  (:require [clojure.string :as str]))

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

(defn page [{:keys [user csrf tokens]}]
  (let [csrf-headers (escape-html
                      (str "{\"X-CSRF-Token\":\"" csrf "\"}"))]
    (str
     "<!doctype html><html><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
     "<title>Animated Graph Cloud</title>"
     "<script src=\"https://cdn.jsdelivr.net/npm/htmx.org@2.0.10/dist/htmx.min.js\" "
     "integrity=\"sha384-H5SrcfygHmAuTDZphMHqBJLc3FhssKjG7w/CeCpFReSfwBWDTKpkzPP8c+cLsK+V\" "
     "crossorigin=\"anonymous\"></script>"
     "<style>body{font-family:system-ui;max-width:70rem;margin:2rem auto;padding:0 1rem}"
     "textarea{width:100%;min-height:18rem}section,article{margin:1.5rem 0}"
     "button{margin:.4rem}.notice{border:2px solid #a66;padding:1rem;overflow-wrap:anywhere}</style>"
     "</head><body hx-headers=\"" csrf-headers "\">"
     "<header><h1>Animated Graph Cloud</h1><p>Signed in as "
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
     "<script>const selection=document.getElementById('picker-selection');"
     "document.getElementById('open-picker').addEventListener('click',()=>{"
     "window.open('/v1/drive/picker','agg-picker','popup,width=960,height=720');});"
     "window.addEventListener('message',event=>{"
     "if(event.origin!==location.origin||event.data?.type!=='agg-picker'){return;}"
     "selection.textContent=event.data.files.map(file=>file.name).join(', ')||'None';"
     "});</script></body></html>")))

(def anonymous-page
  (str "<!doctype html><html><head><meta charset=\"utf-8\">"
       "<title>Animated Graph Cloud</title></head><body>"
       "<h1>Animated Graph Cloud</h1>"
       "<a href=\"/v1/auth/login/start\">Sign in with Google</a>"
       "</body></html>"))
