(ns agg.ui.core
  (:require [agg.admin.core :as admin]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

(defn escape-html [value]
  (-> (str (or value ""))
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#x27;")))

(defn- icon-links []
  (str "<link rel=\"icon\" href=\"/favicon.svg\" type=\"image/svg+xml\">"
       "<link rel=\"icon\" href=\"/favicon-32.png\" type=\"image/png\" sizes=\"32x32\">"
       "<link rel=\"icon\" href=\"/favicon-16.png\" type=\"image/png\" sizes=\"16x16\">"
       "<link rel=\"apple-touch-icon\" sizes=\"180x180\" href=\"/apple-touch-icon.png\">"
       "<meta name=\"theme-color\" content=\"#152238\">"))

(defn- title-case [value]
  (-> value
      (str/replace #"[_-]+" " ")
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

(defn member-panel
  ([members]
   (member-panel members true))
  ([members logs-enabled?]
   (str
    "<section id=\"members\"><h2>Member administration</h2>"
    (when logs-enabled?
      "<p><a href=\"/ui/admin/logs\">View operational logs</a></p>")
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
    "</ul></section>")))

(defn- url-value [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn- logs-query [{:keys [view severity component]}]
  (str "?view=" (url-value view)
       (when severity (str "&severity=" (url-value severity)))
       (when component (str "&component=" (url-value component)))))

(defn- selected-attribute [value selected]
  (when (= value selected) " selected"))

(defn- json-script [value]
  (-> (json/write-str value)
      (str/replace "<" "\\u003c")
      (str/replace ">" "\\u003e")
      (str/replace "&" "\\u0026")))

(defn- picker-script [picker-config]
  (let [config (when picker-config
                 {"accessToken" (:access-token picker-config)
                  "apiKey" (:api-key picker-config)
                  "appId" (:app-id picker-config)})]
    (str
     "(function(){"
     "const pickerConfig=" (json-script config) ";"
     "const selection=document.getElementById('picker-selection');"
     "function reportDiagnostic(phase,view='unknown',listState='unknown'){"
     "fetch('/v1/drive/picker/diagnostic',{method:'POST',credentials:'same-origin',keepalive:true,"
     "headers:{'Content-Type':'application/json','X-CSRF-Token':"
     (json-script (:csrf picker-config)) "},"
     "body:JSON.stringify({phase,view,listState})}).catch(()=>{});}"
     "function pickerCallback(data){"
     "if(data.action===google.picker.Action.LOADED){reportDiagnostic('loaded','drive','unknown');}"
     "if(data.action===google.picker.Action.PICKED){"
     "const d=data.docs&&data.docs[0];"
     "const file=d?{id:d.id,name:d.name,mimeType:d.mimeType}:null;"
     "if(!file||typeof file.id!=='string'||!file.id||typeof file.mimeType!=='string'||!file.mimeType.startsWith('video/')){"
     "selection.textContent='Choose a video file';reportDiagnostic('error','drive','unknown');return;}"
     "document.getElementById('source-video-file-id').value=file.id;"
     "selection.textContent=file.name||'Selected video';"
     "reportDiagnostic('selected','drive','selected');picker.setVisible(false);syncRequest(false);}"
     "if(data.action===google.picker.Action.CANCEL){picker.setVisible(false);reportDiagnostic('cancelled','drive','unknown');}}"
     "let picker=null,pickerRequested=false,pickerLoading=false,pickerLoadTimer=null,pickerLoadAttempt=0;"
     "function failPickerInitialization(attempt){"
     "if(!pickerLoading||attempt!==pickerLoadAttempt)return;"
     "pickerLoading=false;picker=null;pickerRequested=false;"
     "if(pickerLoadTimer){clearTimeout(pickerLoadTimer);pickerLoadTimer=null;}"
     "selection.textContent='Google Drive Picker failed to load. Try again.';"
     "reportDiagnostic('error','drive','unknown');}"
     "function showPicker(){"
     "if(!picker){pickerRequested=true;selection.textContent='Loading Google Drive Picker…';initializePicker();return;}"
     "pickerRequested=false;picker.setVisible(true);reportDiagnostic('opened','drive','unknown');}"
     "function openPicker(){"
     "if(!pickerConfig){selection.textContent='Connect Drive first';return;}"
     "showPicker();}"
     "function initializePicker(){"
     "if(!pickerConfig||picker||pickerLoading)return;"
     "pickerLoading=true;const attempt=++pickerLoadAttempt;"
     "const failed=()=>failPickerInitialization(attempt);"
     "pickerLoadTimer=setTimeout(failed,10000);"
     "try{gapi.load('picker',{callback:()=>{"
     "if(!pickerLoading||attempt!==pickerLoadAttempt)return;"
     "try{const view=new google.picker.DocsView().setIncludeFolders(false)"
     ".setSelectFolderEnabled(false);"
     "const upload=new google.picker.DocsUploadView().setIncludeFolders(false);"
     "picker=new google.picker.PickerBuilder().addView(view).addView(upload)"
     ".setSelectableMimeTypes('video/mp4,video/quicktime,video/webm,video/mpeg,video/ogg,video/x-msvideo,video/x-matroska')"
     ".setOAuthToken(pickerConfig.accessToken).setDeveloperKey(pickerConfig.apiKey)"
     ".setAppId(pickerConfig.appId).setOrigin(location.origin)"
     ".setCallback(pickerCallback).build();"
     "picker.setVisible(false);pickerLoading=false;"
     "if(pickerLoadTimer){clearTimeout(pickerLoadTimer);pickerLoadTimer=null;}"
     "if(pickerRequested)showPicker();}catch(_error){failed();}},"
     "onerror:failed,timeout:10000,ontimeout:failed});}catch(_error){failed();}}"
     "if(pickerConfig)initializePicker();"
     "document.getElementById('open-picker').addEventListener('click',openPicker);"
     "})();")))

(defn- log-value [value]
  (if (vector? value)
    (str/join ", " (map str value))
    (str value)))

(defn- formatted-log [{:keys [createdAt fields]}]
  (let [{:keys [severity component event message]} fields]
    (str "<article class=\"log-entry\"><header><time>"
         (escape-html createdAt) "</time><span class=\"log-level\">"
         (escape-html severity) "</span><code>"
         (escape-html (str component " / " (title-case event)))
         "</code></header>"
         (when message
           (str "<p class=\"log-message\">" (escape-html message) "</p>"))
         "<dl>"
         (apply str
                (for [[key value] (sort-by (comp str key) fields)
                      :when (not= key :message)]
                  (str "<dt>" (escape-html (name key)) "</dt><dd>"
                       (escape-html (log-value value)) "</dd>")))
         "</dl></article>")))

(defn- raw-log [{:keys [raw]}]
  (str "<article class=\"log-entry\"><pre>" (escape-html raw) "</pre></article>"))

(defn logs-page [{:keys [user logs view severity component]}]
  (let [raw? (= "raw" view)
        toggle-view (if raw? "formatted" "raw")
        toggle-label (if raw? "Formatted view" "Raw JSON view")
        severities ["DEBUG" "INFO" "NOTICE" "WARNING" "ERROR"]]
    (str
     "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
     "<meta name=\"color-scheme\" content=\"light\">" (icon-links)
     "<title>Operational logs · Alpha Compose</title>"
     "<style>"
     ":root{font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;color:#152238;background:#f5f7fb;line-height:1.45}"
     "*{box-sizing:border-box}body{margin:0}.shell{max-width:78rem;margin:0 auto;padding:2rem 1.25rem 4rem}"
     "header{display:flex;justify-content:space-between;gap:1rem;align-items:end;margin:1rem 0 2rem}"
     "h1,h2,p{margin-top:0}h1{font-size:clamp(2rem,4vw,3.4rem);letter-spacing:-.05em;margin-bottom:.35rem}"
     ".muted{color:#5c6b82}.eyebrow{color:#4374c5;font-size:.75rem;font-weight:800;letter-spacing:.12em;text-transform:uppercase}"
     ".card{background:white;border:1px solid #e1e7f0;border-radius:1.1rem;box-shadow:0 1rem 3rem #243b5a0d;padding:1.35rem;margin:1rem 0}"
     ".filters{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:1rem;align-items:end}label{display:block;font-weight:700;font-size:.9rem}input,select{font:inherit;width:100%;border:1px solid #cbd5e1;border-radius:.65rem;background:#fff;color:#152238;padding:.68rem .75rem;margin-top:.4rem}"
     "button,.button{border:0;border-radius:.65rem;padding:.7rem 1rem;font-weight:800;cursor:pointer;background:#e8eef8;color:#1a3154;text-decoration:none;display:inline-block}.primary{background:#4374c5;color:white;box-shadow:0 .35rem .8rem #4374c533}"
     ".actions{display:flex;gap:.7rem;align-items:center;flex-wrap:wrap;margin-top:1rem}.log-entry{border-top:1px solid #e8edf4;padding:1rem 0}.log-entry:first-child{border-top:0;padding-top:0}.log-entry header{display:flex;justify-content:flex-start;align-items:center;gap:.65rem;margin:0 0 .5rem;flex-wrap:wrap}.log-entry time{color:#718097;font-size:.85rem}.log-level{border-radius:999px;background:#e8eef8;padding:.2rem .55rem;font-size:.75rem;font-weight:800}.log-entry code,pre,dt,dd{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}.log-message{font-weight:700}.log-entry dl{display:grid;grid-template-columns:max-content 1fr;gap:.25rem .9rem;margin:0}.log-entry dt{color:#718097}.log-entry dd{margin:0;overflow-wrap:anywhere}.log-entry pre{white-space:pre-wrap;overflow:auto;background:#f8fafc;border-radius:.65rem;padding:1rem;margin:0;font-size:.82rem}.empty{padding:2rem 0;text-align:center;color:#5c6b82}footer{margin-top:2rem;color:#6c7a90}footer a{color:#315b9d;margin-right:.75rem}@media(max-width:680px){.shell{padding:1rem .8rem 3rem}header{display:block}.filters{grid-template-columns:1fr}.log-entry dl{grid-template-columns:1fr}.log-entry dt{margin-top:.5rem}}"
     "</style></head><body><div class=\"shell\"><header><div><div class=\"eyebrow\">Administration</div><h1>Operational logs</h1><p class=\"muted\">Safe structured events retained for 30 days. Showing up to 100 recent entries.</p></div><p class=\"muted\">Signed in as "
     (escape-html (:email user)) "</p></header>"
     "<p><a href=\"/\">← Back to compose</a></p>"
     "<section class=\"card\"><form method=\"get\" action=\"/ui/admin/logs\"><div class=\"filters\">"
     "<label>Severity<select name=\"severity\"><option value=\"\">All severities</option>"
     (apply str (for [option severities]
                  (str "<option value=\"" option "\""
                       (selected-attribute option severity) ">"
                       option "</option>")))
     "</select></label><label>Component<input name=\"component\" maxlength=\"64\" value=\""
     (escape-html component) "\"></label><input type=\"hidden\" name=\"view\" value=\""
     (escape-html view) "\"><button class=\"primary\" type=\"submit\">Apply filters</button></div></form>"
     "<div class=\"actions\"><a class=\"button\" href=\"/ui/admin/logs"
     (logs-query {:view toggle-view :severity severity :component component}) "\">"
     toggle-label "</a><span class=\"muted\">" (count logs) " entries</span></div></section>"
     "<section class=\"card\"><h2>" (if raw? "Raw JSON" "Formatted events") "</h2>"
     (if (seq logs)
       (apply str (map #(if raw? (raw-log %) (formatted-log %)) logs))
       "<p class=\"empty\">No matching logs in the retention window.</p>")
     "</section><footer><a href=\"/privacy\">Privacy</a> · <a href=\"/terms\">Terms</a></footer>"
     "</div></body></html>")))

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

(defn preview-failure-fragment
  [{:keys [category request-id status failureCode line stage elapsedMs timeoutMs
           retryable]}]
  (str "<article id=\"preview-result\" class=\"preview-error\" role=\"alert\"><h2>Preview failed</h2><dl>"
       "<dt>Category</dt><dd><code>" (escape-html category) "</code></dd>"
       "<dt>Request ID</dt><dd><code>" (escape-html request-id) "</code></dd>"
       (when status
         (str "<dt>Status</dt><dd>" (escape-html status) "</dd>"))
       (when failureCode
         (str "<dt>Failure code</dt><dd><code>"
              (escape-html failureCode) "</code></dd>"))
       (when line
         (str "<dt>Source line</dt><dd>" (escape-html line) "</dd>"))
       (when stage
         (str "<dt>Stage</dt><dd><code>" (escape-html stage) "</code></dd>"))
       (when elapsedMs
         (str "<dt>Elapsed</dt><dd>" (escape-html elapsedMs) " ms</dd>"))
       (when timeoutMs
         (str "<dt>Deadline</dt><dd>" (escape-html timeoutMs) " ms</dd>"))
       (when retryable
         "<dt>Retryable</dt><dd>Yes</dd>")
       "</dl></article>"))

(defn page [{:keys [user csrf picker-config tokens members logs-enabled?]}]
  (let [csrf-headers (escape-html
                      (str "{\"X-CSRF-Token\":\"" csrf "\"}"))]
    (str
     "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
     "<meta name=\"color-scheme\" content=\"light\">" (icon-links)
     "<title>Alpha Compose</title>"
     "<script src=\"https://cdn.jsdelivr.net/npm/htmx.org@2.0.10/dist/htmx.min.js\" "
     "integrity=\"sha384-H5SrcfygHmAuTDZphMHqBJLc3FhssKjG7w/CeCpFReSfwBWDTKpkzPP8c+cLsK+V\" "
     "crossorigin=\"anonymous\"></script>"
     (when picker-config
       "<script src=\"https://apis.google.com/js/api.js\"></script>")
     "<style>"
     ":root{font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;color:#152238;background:#f5f7fb;line-height:1.45}"
     "*{box-sizing:border-box}body{margin:0}.shell{max-width:78rem;margin:0 auto;padding:2rem 1.25rem 4rem}"
     "header{display:flex;justify-content:space-between;gap:1rem;align-items:end;margin:1rem 0 2rem}"
     "h1,h2,h3,p{margin-top:0}h1{font-size:clamp(2rem,4vw,3.4rem);letter-spacing:-.05em;margin-bottom:.35rem}h2{font-size:1.25rem;margin-bottom:.35rem}"
     ".muted,.hint{color:#5c6b82}.eyebrow{color:#4374c5;font-size:.75rem;font-weight:800;letter-spacing:.12em;text-transform:uppercase}"
     ".card{background:white;border:1px solid #e1e7f0;border-radius:1.1rem;box-shadow:0 1rem 3rem #243b5a0d;padding:1.35rem;margin:1rem 0}"
     ".drive-card{display:flex;align-items:center;justify-content:space-between;gap:1rem;background:#152238;color:white;border:0}.drive-card .muted{color:#b9c6d9}"
     ".drive-card a{color:#dce9ff}.drive-actions{display:flex;align-items:center;gap:.8rem;flex-wrap:wrap}"
     ".section-head{display:flex;justify-content:space-between;align-items:start;gap:1rem;margin-bottom:1rem}.step{color:#8794a8;font-weight:800;font-size:.8rem}"
     ".field-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1rem}.full{grid-column:1/-1}label,.field-label{display:block;font-weight:700;font-size:.9rem}"
     "label small,.field-label small{display:block;color:#718097;font-weight:400;margin-top:.2rem}input,select,textarea{font:inherit;width:100%;border:1px solid #cbd5e1;border-radius:.65rem;background:#fff;color:#152238;padding:.68rem .75rem;margin-top:.4rem}"
     "input:focus,select:focus,textarea:focus{outline:3px solid #4374c533;border-color:#4374c5}input[type=file]{padding:.5rem;background:#f8fafc}"
     "textarea{min-height:8rem;resize:vertical;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.82rem}"
     ".source-box{background:#f8fafc;border:1px dashed #cbd5e1;border-radius:.8rem;padding:1rem}.source-box textarea{background:white}"
     ".optional{border-top:1px solid #e8edf4;margin-top:1.25rem;padding-top:1.25rem}.toggle{display:flex;align-items:center;gap:.65rem;font-weight:700}.toggle input{width:auto;margin:0}"
     ".actions{display:flex;align-items:center;gap:.75rem;flex-wrap:wrap;margin-top:1.25rem}.button,button{border:0;border-radius:.65rem;padding:.7rem 1rem;font-weight:800;cursor:pointer;background:#e8eef8;color:#1a3154}"
     ".button.primary,button.primary{background:#4374c5;color:white;box-shadow:0 .35rem .8rem #4374c533}.button:hover,button:hover{filter:brightness(.97)}"
     ".status{min-height:1.4rem;color:#5c6b82;font-size:.9rem}.status.error{color:#b42318}.status.success{color:#16734a}"
     "details summary{cursor:pointer;font-weight:800;color:#315b9d}.raw-panel textarea{min-height:18rem}.raw-actions{display:flex;gap:.65rem;flex-wrap:wrap;margin-top:.7rem}.json-errors{white-space:pre-line}.field-reference{margin:.75rem 0 0;padding-left:1.25rem}.field-reference li{margin:.35rem 0}.field-reference code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}"
     ".results{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1rem}.results figure{margin:0}.results img{max-width:100%;border:1px solid #d9e1ed;border-radius:.75rem;background:#eef2f7}"
     ".job{margin:0}.notice{border:2px solid #d8a94d;padding:1rem;overflow-wrap:anywhere}.notice code{display:block;margin-top:.6rem;white-space:pre-wrap}"
     ".inline{display:inline}footer{margin-top:2rem;color:#6c7a90}footer a{color:#315b9d;margin-right:.75rem}"
     "@media(max-width:680px){.shell{padding:1rem .8rem 3rem}header,.drive-card,.section-head{display:block}.field-grid,.results{grid-template-columns:1fr}.drive-actions{margin-top:1rem}}"
     "</style>"
     "</head><body hx-headers=\"" csrf-headers "\">"
     "<div class=\"shell\"><header><div><div class=\"eyebrow\">Telemetry overlays for video</div><h1>Compose your overlay</h1><p class=\"muted\">Configure a render, preview it, then send the finished overlay to Drive. Finished renders use durable jobs, including full-length sections.</p></div>"
     "<p class=\"muted\">Signed in as " (escape-html (:email user)) "</p></header>"
     "<section class=\"card drive-card\"><div><h2>Google Drive</h2><p class=\"muted\">Select one video to compose telemetry over it, or leave it empty for a transparent overlay. The Picker also includes an Upload tab for source videos.</p></div><div class=\"drive-actions\"><a href=\"/v1/auth/drive/start\">Connect Drive</a><button id=\"open-picker\" type=\"button\">Pick one video</button><span>Selected: <output id=\"picker-selection\">None</output></span></div></section>"
     "<section class=\"card\"><div class=\"section-head\"><div><h2>Optional source video</h2><p class=\"muted\">The server verifies the selected Drive file. The browser sends only its ID.</p></div></div><input id=\"source-video-file-id\" type=\"hidden\"><div class=\"field-grid\"><label>Output<select id=\"output-format\"><option value=\"h264-mp4\">H.264 MP4</option><option value=\"prores-422-mov\">ProRes 422 MOV</option></select></label><label>Fit<select id=\"fit-mode\"><option value=\"letterbox\">Letterbox / pillarbox</option><option value=\"crop\">Crop to fill</option></select></label><label>Audio<select id=\"audio-mode\"><option value=\"source+heartbeat\">Source + heartbeat</option><option value=\"source-only\">Source only</option><option value=\"heartbeat-only\">Heartbeat only</option></select></label></div></section>"
     "<form id=\"render-form\" hx-post=\"/ui/jobs\" hx-target=\"#job-result\" hx-swap=\"innerHTML\">"
     "<input id=\"render-request\" type=\"hidden\" name=\"request\" value=\"{}\">"
     "<section class=\"card\"><div class=\"section-head\"><div><div class=\"step\">Step 1</div><h2>Choose your data</h2><p class=\"muted\">Select a supported telemetry format, then upload a file or paste its contents.</p></div></div>"
     "<div class=\"field-grid\"><label>Telemetry format<select id=\"telemetry-format\" required><option value=\"polar-csv\">Polar CSV</option><option value=\"garmin-fit\">Garmin FIT</option><option value=\"oxiwear-hr-csv\">OxiWear heart-rate CSV</option></select></label>"
     "<label>Render preset<select id=\"preset\" required><option value=\"1080p25\">1080p · 25 fps · up to 8 minutes</option><option value=\"2.7k25\">2.7K · 25 fps · up to 4 minutes</option></select></label>"
     "<div class=\"source-box full\"><label for=\"telemetry-file\">Telemetry file <small>CSV or FIT files are read locally in your browser.</small></label><input id=\"telemetry-file\" type=\"file\" accept=\".csv,text/csv\"><label for=\"telemetry\" style=\"margin-top:1rem\">Or paste telemetry content</label><textarea id=\"telemetry\" placeholder=\"Paste CSV text, or base64-encoded FIT content\" required></textarea><p id=\"telemetry-status\" class=\"status\" role=\"status\"></p></div></div></section>"
     "<section class=\"card\"><div class=\"section-head\"><div><div class=\"step\">Step 2</div><h2>Line up the timeline</h2><p class=\"muted\">Use the same timezone for camera, section, and timer values. Telemetry timestamps stay as provided by the file.</p></div></div>"
     "<div class=\"field-grid\"><label>Timezone<select id=\"timezone\"><option value=\"local\">My browser timezone</option><option value=\"UTC\">UTC</option><option value=\"Europe/Warsaw\">Europe/Warsaw</option><option value=\"Europe/London\">Europe/London</option><option value=\"America/New_York\">America/New_York</option><option value=\"America/Los_Angeles\">America/Los_Angeles</option><option value=\"Asia/Tokyo\">Asia/Tokyo</option><option value=\"Australia/Sydney\">Australia/Sydney</option></select><small>Values are converted to absolute ISO-8601 instants.</small></label>"
     "<label>Telemetry sync time<input id=\"telemetry-sync-at\" type=\"datetime-local\" step=\"1\" required><small>Timestamp in the telemetry file at the camera sync moment.</small></label>"
     "<label>Camera sync time<input id=\"camera-sync-at\" type=\"datetime-local\" step=\"1\" required></label>"
     "<label>Section start<input id=\"section-start-at\" type=\"datetime-local\" step=\"1\" required></label>"
     "<label>Section end<input id=\"section-end-at\" type=\"datetime-local\" step=\"1\" required></label></div></section>"
     "<section class=\"card\"><div class=\"section-head\"><div><div class=\"step\">Step 3</div><h2>Optional overlays</h2><p class=\"muted\">Add supporting data only when it is present in this render.</p></div></div>"
     "<div class=\"optional\"><label class=\"toggle\"><input id=\"spo2-enabled\" type=\"checkbox\"> Include OxiWear SpO₂</label><div id=\"spo2-fields\" hidden class=\"source-box\"><label for=\"spo2-file\">SpO₂ CSV file</label><input id=\"spo2-file\" type=\"file\" accept=\".csv,text/csv\"><label for=\"spo2-telemetry\" style=\"margin-top:1rem\">Or paste SpO₂ CSV</label><textarea id=\"spo2-telemetry\" placeholder=\"reading_time,spo2\n2026-07-17T10:00:00Z,97\"></textarea><p id=\"spo2-status\" class=\"status\" role=\"status\"></p></div></div>"
     "<div class=\"optional\"><label class=\"toggle\"><input id=\"timer-enabled\" type=\"checkbox\"> Show elapsed timer</label><div id=\"timer-fields\" hidden class=\"field-grid\"><label>Timer start<input id=\"timer-start-at\" type=\"datetime-local\" step=\".001\"></label><label>Timer end<input id=\"timer-end-at\" type=\"datetime-local\" step=\".001\"></label></div></div>"
     "<div class=\"optional\"><label class=\"toggle\"><input id=\"watermark-enabled\" type=\"checkbox\"> Add a PNG watermark</label><div id=\"watermark-fields\" hidden class=\"source-box\"><label for=\"watermark-file\">PNG file <small>It is converted to base64 locally and sent with this request.</small></label><input id=\"watermark-file\" type=\"file\" accept=\"image/png,.png\"><p id=\"watermark-status\" class=\"status\" role=\"status\"></p></div></div></section>"
     "<section class=\"card raw-panel\"><details><summary>Advanced: paste or inspect raw JSON</summary><p class=\"hint\">Paste a request and choose “Apply to form”. The JSON is checked for structural errors first; form edits are reflected here before preview or submission.</p><p class=\"hint\"><strong>Accepted fields</strong></p><ul class=\"field-reference\"><li><code>telemetryFormat</code> and <code>telemetry</code> are required. Formats: <code>polar-csv</code>, <code>garmin-fit</code> (base64 FIT), or <code>oxiwear-hr-csv</code>.</li><li><code>preset</code> is required: <code>1080p25</code> (1920×1080, 25 fps, up to 8 minutes) or <code>2.7k25</code> (2704×1520, 25 fps, up to 4 minutes).</li><li><code>telemetrySyncAt</code>, <code>cameraSyncAt</code>, <code>sectionStartAt</code>, and <code>sectionEndAt</code> are required ISO-8601 instants with <code>Z</code> or an explicit UTC offset.</li><li><code>spo2</code> is optional: <code>{format:\"oxiwear-spo2-csv\", telemetry}</code>.</li><li><code>timer</code> is optional: <code>{startAt, endAt}</code>, within the requested section.</li><li><code>watermark</code> is optional: <code>{contentBase64}</code>, a bounded PNG encoded as base64.</li><li><code>sourceVideo</code> is optional: <code>{fileId}</code>; when present, <code>outputFormat</code> (<code>h264-mp4</code> or <code>prores-422-mov</code>), <code>fitMode</code> (<code>letterbox</code>, <code>pillarbox</code>, or <code>crop</code>), and <code>audioMode</code> (<code>source+heartbeat</code>, <code>source-only</code>, or <code>heartbeat-only</code>) configure compositing.</li></ul><textarea id=\"raw-json\" spellcheck=\"false\">{}</textarea><div class=\"raw-actions\"><button id=\"apply-json\" type=\"button\">Apply JSON to form</button><button id=\"copy-json\" type=\"button\">Copy generated JSON</button></div><p id=\"json-status\" class=\"status json-errors\" role=\"status\"></p></details></section>"
     "<section class=\"card\"><div class=\"actions\"><button class=\"primary\" type=\"button\" hx-post=\"/ui/preview\" hx-include=\"closest form\" hx-target=\"#preview-result\" hx-swap=\"outerHTML\">Preview overlay</button><button class=\"primary\" type=\"submit\">Submit render</button><span id=\"form-status\" class=\"status\" role=\"status\"></span></div></section></form>"
     "<div class=\"results\"><div id=\"preview-result\"></div><div id=\"job-result\"></div></div>"
     (token-panel tokens)
     (when (admin/administrator? (:role user))
       (member-panel members logs-enabled?))
     "<footer><a href=\"/privacy\">Privacy</a> · <a href=\"/terms\">Terms</a></footer>"
     "<script>(function(){"
     "const form=document.getElementById('render-form'), hidden=document.getElementById('render-request'), raw=document.getElementById('raw-json');"
     "const status=document.getElementById('form-status'), jsonStatus=document.getElementById('json-status');"
     "const byId=id=>document.getElementById(id), value=id=>byId(id).value.trim();"
     "function show(node,message,kind){node.textContent=message;node.className='status'+(kind?' '+kind:'');}"
     "function activeZone(){const selected=value('timezone');return selected==='local'?Intl.DateTimeFormat().resolvedOptions().timeZone:selected;}"
     "function dateParts(instant,zone){const parts=new Intl.DateTimeFormat('en-US',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(new Date(instant));return Object.fromEntries(parts.filter(part=>part.type!=='literal').map(part=>[part.type,Number(part.value)]));}"
     "function localToIso(input){const text=value(input);if(!text)throw new Error(input+' is required');const [date,time]=text.split('T'),dateValues=date.split('-').map(Number),timeValues=time.split(':');if(dateValues.length!==3||timeValues.length<2)throw new Error('Enter a valid date and time');const seconds=Number(timeValues[2]||0),wholeSeconds=Math.trunc(seconds),milliseconds=Math.round((seconds-wholeSeconds)*1000),target=Date.UTC(dateValues[0],dateValues[1]-1,dateValues[2],Number(timeValues[0]),Number(timeValues[1]),wholeSeconds)+milliseconds;let guess=target;for(let i=0;i<4;i++){const parts=dateParts(guess,activeZone()),shown=Date.UTC(parts.year,parts.month-1,parts.day,parts.hour,parts.minute,parts.second)+milliseconds;guess+=target-shown;}const result=new Date(guess);if(Number.isNaN(result.getTime()))throw new Error('Enter a valid date and time');return result.toISOString();}"
     "function isoToLocal(instant){const date=new Date(instant);if(Number.isNaN(date.getTime()))throw new Error('Invalid ISO-8601 timestamp: '+instant);const parts=dateParts(date.getTime(),activeZone()),milliseconds=date.getUTCMilliseconds(),fraction=milliseconds?'.'+String(milliseconds).padStart(3,'0'):'';return [parts.year,String(parts.month).padStart(2,'0'),String(parts.day).padStart(2,'0')].join('-')+'T'+[String(parts.hour).padStart(2,'0'),String(parts.minute).padStart(2,'0'),String(parts.second).padStart(2,'0')].join(':')+fraction;}"
     "const fileBackedValues=Object.create(null);function contentValue(id){return Object.prototype.hasOwnProperty.call(fileBackedValues,id)?String(fileBackedValues[id]).trim():value(id);}function setFileBackedValue(id,content){fileBackedValues[id]=content;byId(id).value=content;}function clearFileBackedValue(id){delete fileBackedValues[id];}"
     "function required(id,label){const result=contentValue(id);if(!result)throw new Error(label+' is required');return result;}"
     "function buildRequest(){const request={telemetryFormat:required('telemetry-format','Telemetry format'),telemetry:required('telemetry','Telemetry'),preset:required('preset','Render preset'),telemetrySyncAt:localToIso('telemetry-sync-at'),cameraSyncAt:localToIso('camera-sync-at'),sectionStartAt:localToIso('section-start-at'),sectionEndAt:localToIso('section-end-at')};const source=value('source-video-file-id');if(source){request.sourceVideo={fileId:source};request.outputFormat=value('output-format');request.fitMode=value('fit-mode');request.audioMode=value('audio-mode');}if(byId('spo2-enabled').checked){request.spo2={format:'oxiwear-spo2-csv',telemetry:required('spo2-telemetry','SpO₂ telemetry')};}if(byId('timer-enabled').checked){request.timer={startAt:localToIso('timer-start-at'),endAt:localToIso('timer-end-at')};}if(byId('watermark-enabled').checked){request.watermark={contentBase64:required('watermark-content','Watermark file')};}return request;}"
     "const requestFields=['telemetryFormat','telemetry','preset','telemetrySyncAt','cameraSyncAt','sectionStartAt','sectionEndAt','spo2','timer','watermark','sourceVideo','outputFormat','fitMode','audioMode'],requiredRequestFields=['telemetryFormat','telemetry','preset','telemetrySyncAt','cameraSyncAt','sectionStartAt','sectionEndAt'],isoPattern=/^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})$/;"
     "function isObject(candidate){return candidate!==null&&typeof candidate==='object'&&!Array.isArray(candidate);}function has(candidate,key){return Object.prototype.hasOwnProperty.call(candidate,key);}function utf8Length(text){return new TextEncoder().encode(text).length;}function instantValue(value){return typeof value==='string'&&isoPattern.test(value)&&!Number.isNaN(Date.parse(value));}function unknownFields(candidate,allowed,path,errors){Object.keys(candidate).filter(key=>!allowed.includes(key)).forEach(key=>errors.push(path+' contains unknown field '+key+'.'));}function requiredString(candidate,key,path,errors){if(!has(candidate,key)||candidate[key]===''){errors.push(path+'.'+key+' is required.');return false;}if(typeof candidate[key]!=='string'){errors.push(path+'.'+key+' must be a string.');return false;}return true;}function validateInstant(candidate,key,path,errors){if(!requiredString(candidate,key,path,errors))return false;if(!instantValue(candidate[key])){errors.push(path+'.'+key+' must be an ISO-8601 instant with Z or an explicit UTC offset.');return false;}return true;}"
     "function validateRequest(request){const errors=[];if(!isObject(request))return ['Request must be a JSON object.'];unknownFields(request,requestFields,'Request',errors);requiredRequestFields.forEach(key=>requiredString(request,key,'Request',errors));if(typeof request.telemetryFormat==='string'&&request.telemetryFormat&&!['polar-csv','garmin-fit','oxiwear-hr-csv'].includes(request.telemetryFormat))errors.push('Request.telemetryFormat must be polar-csv, garmin-fit, or oxiwear-hr-csv.');if(typeof request.preset==='string'&&request.preset&&!['1080p25','2.7k25'].includes(request.preset))errors.push('Request.preset must be 1080p25 or 2.7k25.');if(typeof request.telemetry==='string'&&request.telemetry){const limit=request.telemetryFormat==='garmin-fit'?13981016:10485760;if(utf8Length(request.telemetry)>limit)errors.push('Request.telemetry exceeds its encoded size limit.');}['telemetrySyncAt','cameraSyncAt','sectionStartAt','sectionEndAt'].forEach(key=>{if(has(request,key)&&typeof request[key]==='string'&&request[key]&&!instantValue(request[key]))errors.push('Request.'+key+' must be an ISO-8601 instant with Z or an explicit UTC offset.');});const sectionTimes=['cameraSyncAt','sectionStartAt','sectionEndAt'].map(key=>Date.parse(request[key]));if(sectionTimes.every(time=>!Number.isNaN(time))&&!(sectionTimes[0]<=sectionTimes[1]&&sectionTimes[1]<sectionTimes[2]))errors.push('Request timestamps must satisfy cameraSyncAt <= sectionStartAt < sectionEndAt.');if(has(request,'spo2')){const value=request.spo2;if(!isObject(value))errors.push('Request.spo2 must be an object.');else{unknownFields(value,['format','telemetry'],'Request.spo2',errors);requiredString(value,'format','Request.spo2',errors);requiredString(value,'telemetry','Request.spo2',errors);if(typeof value.format==='string'&&value.format&&value.format!=='oxiwear-spo2-csv')errors.push('Request.spo2.format must be oxiwear-spo2-csv.');if(typeof value.telemetry==='string'&&value.telemetry&&utf8Length(value.telemetry)>10485760)errors.push('Request.spo2.telemetry exceeds the 10 MiB limit.');}}if(has(request,'timer')){const value=request.timer;if(!isObject(value))errors.push('Request.timer must be an object.');else{unknownFields(value,['startAt','endAt'],'Request.timer',errors);const startValid=validateInstant(value,'startAt','Request.timer',errors),endValid=validateInstant(value,'endAt','Request.timer',errors);if(startValid&&endValid){const start=Date.parse(value.startAt),end=Date.parse(value.endAt),sectionStart=Date.parse(request.sectionStartAt),sectionEnd=Date.parse(request.sectionEndAt);if(!Number.isNaN(sectionStart)&&!Number.isNaN(sectionEnd)&&!(sectionStart<=start&&start<end&&end<=sectionEnd))errors.push('Request.timer must satisfy sectionStartAt <= startAt < endAt <= sectionEndAt.');}}}if(has(request,'watermark')){const value=request.watermark;if(!isObject(value))errors.push('Request.watermark must be an object.');else{unknownFields(value,['contentBase64'],'Request.watermark',errors);if(requiredString(value,'contentBase64','Request.watermark',errors)&&(!/^[A-Za-z0-9+/]*={0,2}$/.test(value.contentBase64)||value.contentBase64.length%4===1))errors.push('Request.watermark.contentBase64 must be base64 text.');if(typeof value.contentBase64==='string'&&value.contentBase64.length>2796204)errors.push('Request.watermark.contentBase64 exceeds the 2 MiB PNG limit.');}}if(has(request,'sourceVideo')){const value=request.sourceVideo;if(!isObject(value))errors.push('Request.sourceVideo must be an object.');else{unknownFields(value,['fileId','name','mimeType'],'Request.sourceVideo',errors);requiredString(value,'fileId','Request.sourceVideo',errors);['name','mimeType'].forEach(key=>{if(has(value,key)&&typeof value[key]!=='string')errors.push('Request.sourceVideo.'+key+' must be a string.');});}}[['outputFormat',['h264-mp4','prores-422-mov']],['fitMode',['letterbox','pillarbox','crop']],['audioMode',['source+heartbeat','source-only','heartbeat-only']]].forEach(([key,allowed])=>{if(has(request,key)){if(typeof request[key]!=='string')errors.push('Request.'+key+' must be a string.');else if(!allowed.includes(request[key]))errors.push('Request.'+key+' has an unsupported value.');if(!has(request,'sourceVideo'))errors.push('Request.'+key+' requires sourceVideo.fileId.');}});return errors;}"
     "function writeRequest(request){const text=JSON.stringify(request,null,2);hidden.value=text;raw.value=text;return text;}"
     "function syncRequest(showError){try{const text=writeRequest(buildRequest());show(status,'Ready to preview or submit.','success');return text;}catch(error){if(showError!==false)show(status,error.message,'error');return null;}}"
     "function refreshOptional(toggleId,fieldsId){byId(fieldsId).hidden=!byId(toggleId).checked;}"
     "[['spo2-enabled','spo2-fields'],['timer-enabled','timer-fields'],['watermark-enabled','watermark-fields']].forEach(([toggle,fields])=>byId(toggle).addEventListener('change',()=>{refreshOptional(toggle,fields);syncRequest(false);}));"
     "function bytesToBase64(buffer){const bytes=new Uint8Array(buffer);let binary='';for(let i=0;i<bytes.length;i+=0x8000)binary+=String.fromCharCode(...bytes.subarray(i,i+0x8000));return btoa(binary);}"
     "function readTextFile(inputId,targetId,statusId){const input=byId(inputId);input.addEventListener('change',()=>{const file=input.files&&input.files[0];if(!file)return;show(byId(statusId),'Reading '+file.name+'…');const reader=new FileReader();reader.onload=()=>{setFileBackedValue(targetId,String(reader.result));show(byId(statusId),'Loaded '+file.name+'.','success');syncRequest(false);};reader.onerror=()=>show(byId(statusId),'Could not read '+file.name+'.','error');reader.readAsText(file);});}"
     "function readBinaryFile(inputId,targetId,statusId){const input=byId(inputId);input.addEventListener('change',()=>{const file=input.files&&input.files[0];if(!file)return;show(byId(statusId),'Reading '+file.name+'…');const reader=new FileReader();reader.onload=()=>{setFileBackedValue(targetId,bytesToBase64(reader.result));show(byId(statusId),'Loaded '+file.name+'.','success');syncRequest(false);};reader.onerror=()=>show(byId(statusId),'Could not read '+file.name+'.','error');reader.readAsArrayBuffer(file);});}"
     "const telemetryFormat=byId('telemetry-format'),telemetryFile=byId('telemetry-file'),telemetryContent=byId('telemetry'),telemetryStatus=byId('telemetry-status');let telemetryReadGeneration=0;"
     "function clearTelemetryFile(){telemetryReadGeneration++;clearFileBackedValue('telemetry');telemetryContent.value='';telemetryFile.value='';show(telemetryStatus,'');}"
     "function readTelemetryFile(){telemetryFile.addEventListener('change',()=>{const generation=++telemetryReadGeneration;clearFileBackedValue('telemetry');telemetryContent.value='';const file=telemetryFile.files&&telemetryFile.files[0];if(!file){show(telemetryStatus,'');syncRequest(false);return;}const binary=telemetryFormat.value==='garmin-fit';show(telemetryStatus,'Reading '+file.name+'…');const reader=new FileReader();reader.onload=()=>{if(generation!==telemetryReadGeneration)return;setFileBackedValue('telemetry',binary?bytesToBase64(reader.result):String(reader.result));show(telemetryStatus,'Loaded '+file.name+'.','success');syncRequest(false);};reader.onerror=()=>{if(generation!==telemetryReadGeneration)return;clearFileBackedValue('telemetry');telemetryContent.value='';show(telemetryStatus,'Could not read '+file.name+'.','error');syncRequest(false);};if(binary)reader.readAsArrayBuffer(file);else reader.readAsText(file);});}"
     "readTelemetryFile();readTextFile('spo2-file','spo2-telemetry','spo2-status');"
     "function updateTelemetryAccept(){telemetryFile.accept=telemetryFormat.value==='garmin-fit'?'.fit,application/octet-stream':'.csv,text/csv';}telemetryFormat.addEventListener('change',()=>{clearTelemetryFile();updateTelemetryAccept();syncRequest(false);});updateTelemetryAccept();"
     "byId('watermark-content')||(()=>{const input=document.createElement('input');input.id='watermark-content';input.type='hidden';form.appendChild(input);})();readBinaryFile('watermark-file','watermark-content','watermark-status');"
     "function applyRequest(request){if(!request||typeof request!=='object'||Array.isArray(request))throw new Error('JSON must be an object');['telemetryFormat','preset','outputFormat','fitMode','audioMode'].forEach(key=>{if(request[key]!==undefined&&byId(key.replace(/[A-Z]/g,letter=>'-'+letter.toLowerCase())))byId(key.replace(/[A-Z]/g,letter=>'-'+letter.toLowerCase())).value=request[key];});byId('source-video-file-id').value=request.sourceVideo?.fileId||'';byId('timezone').value='local';[['telemetrySyncAt','telemetry-sync-at'],['cameraSyncAt','camera-sync-at'],['sectionStartAt','section-start-at'],['sectionEndAt','section-end-at']].forEach(([key,id])=>{byId(id).value=request[key]?isoToLocal(request[key]):'';});clearFileBackedValue('telemetry');byId('telemetry').value=request.telemetry||'';byId('telemetry-file').value='';show(byId('telemetry-status'),request.telemetry?'Loaded from JSON.':'');const hasSpo2=!!request.spo2;byId('spo2-enabled').checked=hasSpo2;refreshOptional('spo2-enabled','spo2-fields');clearFileBackedValue('spo2-telemetry');byId('spo2-telemetry').value=hasSpo2?(request.spo2.telemetry||''):'';show(byId('spo2-status'),hasSpo2?'Loaded from JSON.':'');const hasTimer=!!request.timer;byId('timer-enabled').checked=hasTimer;refreshOptional('timer-enabled','timer-fields');byId('timer-start-at').value=hasTimer?isoToLocal(request.timer.startAt):'';byId('timer-end-at').value=hasTimer?isoToLocal(request.timer.endAt):'';const hasWatermark=!!request.watermark;byId('watermark-enabled').checked=hasWatermark;refreshOptional('watermark-enabled','watermark-fields');byId('watermark-file').value='';clearFileBackedValue('watermark-content');byId('watermark-content').value=hasWatermark?(request.watermark.contentBase64||''):'';show(byId('watermark-status'),hasWatermark?'Loaded from JSON.':'');updateTelemetryAccept();writeRequest(request);show(jsonStatus,'JSON applied to the form.','success');show(status,'Ready to preview or submit.','success');}"
     "byId('apply-json').addEventListener('click',()=>{try{const request=JSON.parse(raw.value),errors=validateRequest(request);if(errors.length){show(jsonStatus,errors.map((error,index)=>(index+1)+'. '+error).join('\\n'),'error');return;}applyRequest(request);}catch(error){show(jsonStatus,error.message,'error');}});byId('copy-json').addEventListener('click',()=>{const text=syncRequest(false)||raw.value;navigator.clipboard?navigator.clipboard.writeText(text).then(()=>show(jsonStatus,'Generated JSON copied.','success')):show(jsonStatus,'Copy is unavailable in this browser.');});"
     "function previewTrigger(event){const trigger=event.detail?.elt||event.target;return !!trigger?.matches?.('[hx-post=\"/ui/preview\"]');}function previewTarget(event){const target=event.detail?.target||event.target;return target?.id==='preview-result'?target:null;}"
     "form.addEventListener('input',event=>{if(event.target.id==='raw-json'||event.target.type==='file')return;clearFileBackedValue(event.target.id);syncRequest(false);});document.body.addEventListener('htmx:configRequest',event=>{if(!form.contains(event.target))return;const text=syncRequest(true);if(!text){event.preventDefault();return;}event.detail.parameters.request=text;if(previewTrigger(event))show(status,'Previewing…');});document.body.addEventListener('htmx:beforeRequest',event=>{if(previewTrigger(event))show(status,'Previewing…');});document.body.addEventListener('htmx:afterSwap',event=>{const target=previewTarget(event);if(!target)return;show(status,target.matches('.preview-error')?'Preview failed. See details below.':'Preview ready.',target.matches('.preview-error')?'error':'success');});"
     (picker-script picker-config)
     "})();</script></div></body></html>")))

(defn- public-page [title body]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       (icon-links)
       "<title>" title " · Alpha Compose</title>"
       "<meta name=\"color-scheme\" content=\"light\">"
       "<style>"
       ":root{font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;color:#152238;background:#f5f7fb;line-height:1.45}"
       "*{box-sizing:border-box}body{margin:0}.shell{max-width:78rem;margin:0 auto;padding:2rem 1.25rem 4rem}"
       ".public-header{display:flex;justify-content:space-between;gap:1rem;align-items:center;margin:1rem 0 2rem}.brand{color:#152238;font-weight:800;letter-spacing:-.03em;text-decoration:none}"
       "nav{display:flex;gap:1rem;flex-wrap:wrap}nav a,footer a{color:#315b9d}"
       "h1,h2,p{margin-top:0}h1{font-size:clamp(2.5rem,6vw,5rem);line-height:1.02;letter-spacing:-.06em;max-width:11ch;margin-bottom:1rem}h2{font-size:1.25rem;margin-bottom:.35rem}"
       ".muted{color:#5c6b82}.eyebrow{color:#4374c5;font-size:.75rem;font-weight:800;letter-spacing:.12em;text-transform:uppercase}"
       ".hero{display:grid;grid-template-columns:minmax(0,1.2fr) minmax(18rem,.8fr);gap:1rem;align-items:stretch;margin:2.5rem 0 1rem}.hero-copy{padding:2rem 0 1rem}"
       ".hero-card,.card{background:white;border:1px solid #e1e7f0;border-radius:1.1rem;box-shadow:0 1rem 3rem #243b5a0d;padding:1.35rem}.hero-card{background:#152238;color:white;border:0;display:flex;flex-direction:column;justify-content:space-between}.hero-card .muted{color:#b9c6d9}"
       ".step{color:#8794a8;font-weight:800;font-size:.8rem}.hero-card .step{color:#9fb8df}.hero-card-note{color:#dce9ff;margin:2rem 0 0}"
       ".actions{display:flex;align-items:center;gap:.75rem;flex-wrap:wrap;margin-top:1.25rem}.button,.cta{border:0;border-radius:.65rem;padding:.7rem 1rem;font-weight:800;cursor:pointer;background:#e8eef8;color:#1a3154;text-decoration:none;display:inline-block}.button.primary,.cta{background:#4374c5;color:white;box-shadow:0 .35rem .8rem #4374c533}"
       ".feature-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:1rem;margin:1rem 0}.feature-grid .card{margin:0}.trust-card{margin-top:1rem}.trust-card p:last-child{margin-bottom:0}"
       "footer{margin-top:2rem;color:#6c7a90}footer a{margin-right:.75rem}"
       "@media(max-width:680px){.shell{padding:1rem .8rem 3rem}.public-header{display:block}.public-header nav{margin-top:1rem}.hero{grid-template-columns:1fr;margin-top:1.5rem}.hero-copy{padding:1rem 0}.feature-grid{grid-template-columns:1fr}}"
       "</style></head><body><div class=\"shell\">"
       "<header class=\"public-header\"><a class=\"brand\" href=\"/\">Alpha Compose</a><nav><a href=\"/privacy\">Privacy</a>"
       "<a href=\"/terms\">Terms</a></nav></header><main>" body "</main>"
       "<footer><small>© 2026 Alpha Compose · <a href=\"mailto:me@jamiep.org\">Contact</a></small></footer>"
       "</div></body></html>"))

(def anonymous-page
  (public-page
   "Telemetry overlays for video"
   (str "<section class=\"hero\"><div class=\"hero-copy\">"
        "<div class=\"eyebrow\">Telemetry overlays for video</div>"
        "<h1>Bring telemetry to your video.</h1>"
        "<p class=\"muted\">Alpha Compose turns heart-rate and other supported telemetry into "
        "transparent video overlays for short videos. Export the overlay, then "
        "place it above your footage in your editor.</p>"
        "<div class=\"actions\"><a class=\"cta\" href=\"/v1/auth/login/start\">Sign in with Google</a></div>"
        "</div><aside class=\"hero-card\"><div><div class=\"step\">How it works</div>"
        "<h2>From telemetry to overlay.</h2>"
        "<p class=\"muted\">Configure your render, preview it, then send the finished overlay to Drive.</p>"
        "</div><p class=\"hero-card-note\">Alpha Compose does not upload or composite your source video.</p>"
        "</aside></section>"
        "<section class=\"feature-grid\"><article class=\"card\"><div class=\"step\">01</div>"
        "<h2>Bring your data</h2><p class=\"muted\">Use supported heart-rate and other telemetry formats from your existing activity files.</p>"
        "</article><article class=\"card\"><div class=\"step\">02</div>"
        "<h2>Preview the result</h2><p class=\"muted\">Line up the timeline, configure the overlay, and preview it before rendering.</p>"
        "</article><article class=\"card\"><div class=\"step\">03</div>"
        "<h2>Keep editing</h2><p class=\"muted\">Export a transparent overlay, then place it above your footage in your editor.</p>"
        "</article></section>"
        "<section class=\"card trust-card\"><div class=\"step\">Access &amp; privacy</div>"
        "<h2>Connect only what you need.</h2>"
        "<p class=\"muted\">Google account information is requested to sign in approved users. "
        "Connecting Google Drive is optional and requests only the "
        "<code>drive.file</code> permission, limited to files you select or "
        "Alpha Compose creates for input selection and output delivery.</p></section>")))

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
