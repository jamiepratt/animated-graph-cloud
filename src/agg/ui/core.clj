(ns agg.ui.core
  (:require [agg.admin.core :as admin]
            [agg.drive.core :as drive]
            [agg.jobs.lifecycle :as jobs]
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

(defn- minor-units->pln [minor-units]
  (let [major (quot minor-units 100)
        minor (mod minor-units 100)]
    (str major "." (when (< minor 10) "0") minor)))

(defn- preview-admission-disclosure []
  (let [preview (minor-units->pln
                 jobs/default-preview-reservation-minor-units)
        total (minor-units->pln
               jobs/default-preview-plus-render-exposure-minor-units)]
    (str "<p class=\"hint preview-admission-cost\"><strong>Admission cost:</strong> "
         "Each Preview attempt reserves up to PLN " preview ". "
         "Preview plus one Submit reserves up to PLN " total ". "
         "Reservations remain counted after success, failure, cancellation, or expiry. "
         "Retrying Preview reserves another PLN " preview ".</p>")))

(defn- icon-links []
  (str "<link rel=\"icon\" href=\"/favicon.svg\" type=\"image/svg+xml\">"
       "<link rel=\"icon\" href=\"/favicon-32.png\" type=\"image/png\" sizes=\"32x32\">"
       "<link rel=\"icon\" href=\"/favicon-16.png\" type=\"image/png\" sizes=\"16x16\">"
       "<link rel=\"apple-touch-icon\" sizes=\"180x180\" href=\"/apple-touch-icon.png\">"
       "<meta name=\"theme-color\" content=\"#031225\">"))

(defn theme-style []
  (str
   ":root{color-scheme:dark;font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;line-height:1.45;"
   "--color-background:#031225;--color-surface:#091d34f2;--color-surface-strong:#0b2440;"
   "--color-surface-soft:#102b49;--color-border:#65d6ff3d;--color-border-strong:#65d6ff73;"
   "--color-text:#f4f9ff;--color-muted:#b8cce0;--color-subtle:#8ca9c2;"
   "--color-link:#7ee4ff;--color-accent:#65d6ff;--color-accent-ink:#031225;"
   "--color-danger:#ff8294;--color-success:#65e1ad;--color-warning:#ffd27a;"
   "--shadow-surface:0 1.25rem 3.5rem #00081466;"
   "color:var(--color-text);background:var(--color-background)}"
   "html{min-width:0;max-width:100%;background:var(--color-background)}"
   "*,*::before,*::after{box-sizing:border-box}"
   "body{margin:0;min-width:0;max-width:100%;overflow-x:clip;color:var(--color-text);"
   "background-color:var(--color-background);"
   "background-image:linear-gradient(180deg,#031225c7 0%,#031225e8 48rem,#031225 78rem),url('/telemetry-background.webp');"
   "background-repeat:no-repeat,no-repeat;background-position:center top,center top;"
   "background-size:100% 100%,min(64rem,100vw) auto}"
   "a{color:var(--color-link);text-underline-offset:.18em}a:hover{color:#b8f2ff}"
   "button,.button,.cta,input,select,textarea,summary,a{touch-action:manipulation}"
   ":focus,:focus-visible{outline:3px solid var(--color-warning);outline-offset:3px}"
   "::selection{color:var(--color-text);background:#be334f99}"
   ".shell,.shell>*{min-width:0}.shell>header{padding:clamp(.9rem,2vw,1.25rem);background:var(--color-surface);border:1px solid var(--color-border);border-radius:1rem;box-shadow:var(--shadow-surface)}.muted,.hint{color:var(--color-muted)}"
   ".eyebrow{color:var(--color-accent)}.step{color:var(--color-subtle)}"
   ".card,.hero-card,.drive-card,.trace-preview,.preview-pending,.preview-error,.preview-stale,.preview-empty,.log-entry{"
   "background:var(--color-surface);border-color:var(--color-border);box-shadow:var(--shadow-surface)}"
   ".hero-card,.drive-card{background:var(--color-surface-strong);border:1px solid var(--color-border-strong)}"
   ".hero-card .muted,.drive-card .muted,.hero-card-note{color:#c8ddef}"
   "button,.button,.cta{background:var(--color-surface-soft);color:var(--color-text);border:1px solid var(--color-border);text-decoration:none}"
   ".button.primary,button.primary,.cta,.primary{background:var(--color-accent);color:var(--color-accent-ink);border-color:var(--color-accent);box-shadow:0 .4rem 1.2rem #0fc3ff2e}"
   ".button:hover,button:hover,.cta:hover{filter:brightness(1.08)}"
   ".button.primary:disabled,button.primary:disabled,.button.primary:disabled:hover,button.primary:disabled:hover{background:#53677b;color:#d2dce6;border-color:#53677b;box-shadow:none;filter:none}"
   "input,select,textarea{background:#06182b;color:var(--color-text);border-color:#6b8ba5}"
   "input::placeholder,textarea::placeholder{color:#8fa9bf;opacity:1}"
   "input[readonly]{background:#10263c;color:#c1d3e4}"
   "input[type=file],.source-box,.log-entry pre{background:#06182b}"
   "input:focus,select:focus,textarea:focus{outline:3px solid var(--color-warning);border-color:var(--color-warning)}"
   ".source-box{border-color:var(--color-border-strong)}.source-box textarea{background:#06182b;color:var(--color-text)}"
   ".optional,.log-entry{border-color:var(--color-border)}"
   ".status{color:var(--color-muted)}.status.error{color:var(--color-danger)}.status.success{color:var(--color-success)}"
   "details summary,nav a,footer a,.drive-card a{color:var(--color-link)}"
   ".results img{border-color:var(--color-border);background:#06182b}"
   ".preview-warning{background:#33270d;border-color:#b98c32;color:#ffe3a3}"
   ".preview-cell .preview-open{background:#06182b}"
   "#preview-dialog{color:var(--color-text);background:var(--color-surface-strong);border:1px solid var(--color-border-strong)}"
   "#preview-dialog::backdrop{background:#010813e6}"
   ".notice{border-color:var(--color-warning);background:#2a230f}.notice code{color:var(--color-text)}"
   ".log-level{background:#173b5a;color:#d9f5ff}.log-entry time,.log-entry dt,.empty,footer{color:var(--color-muted)}"
   "@media(max-width:680px){body{background-size:100% 100%,100vw auto;background-position:center top,center top}}"))

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

(defn picker-policy-script []
  (let [mime-types (str/join "," drive/supported-source-video-mime-types)]
    (str "const pickerMimeTypes=" (json-script mime-types) ";"
         "const pickerMimeTypeSet=new Set(pickerMimeTypes.split(','));")))

(defn picker-views-script []
  (str
   "function configureVideoDriveView(view){return view"
   ".setMimeTypes(pickerMimeTypes)"
   ".setIncludeFolders(true)"
   ".setSelectFolderEnabled(false)"
   ".setMode(google.picker.DocsViewMode.LIST);}"
   "const driveView=configureVideoDriveView(new google.picker.DocsView());"
   "const sharedDrivesView=configureVideoDriveView(new google.picker.DocsView())"
   ".setEnableDrives(true);"
   "const upload=new google.picker.DocsUploadView().setIncludeFolders(false);"))

(defn- picker-script [picker-config]
  (let [config (when picker-config
                 {"accessToken" (:access-token picker-config)
                  "apiKey" (:api-key picker-config)
                  "appId" (:app-id picker-config)})]
    (str
     "(function(){"
     "const pickerConfig=" (json-script config) ";"
     (picker-policy-script)
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
     "if(!file||typeof file.id!=='string'||!file.id||typeof file.mimeType!=='string'||!pickerMimeTypeSet.has(file.mimeType)){"
     "selection.textContent='Choose a video file';reportDiagnostic('error','drive','unknown');return;}"
     "document.getElementById('source-video-file-id').value=file.id;"
     "selection.textContent=file.name||'Selected video';"
     "reportDiagnostic('selected','drive','selected');picker.setVisible(false);invalidatePreview();syncRequest(false);}"
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
     "if(!pickerConfig){selection.textContent='Google Drive is temporarily unavailable';return;}"
     "showPicker();}"
     "function initializePicker(){"
     "if(!pickerConfig||picker||pickerLoading)return;"
     "pickerLoading=true;const attempt=++pickerLoadAttempt;"
     "const failed=()=>failPickerInitialization(attempt);"
     "pickerLoadTimer=setTimeout(failed,10000);"
     "try{gapi.load('picker',{callback:()=>{"
     "if(!pickerLoading||attempt!==pickerLoadAttempt)return;"
     "try{" (picker-views-script)
     "picker=new google.picker.PickerBuilder()"
     ".addView(driveView).addView(sharedDrivesView).addView(upload)"
     ".setSelectableMimeTypes(pickerMimeTypes)"
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
     "<meta name=\"color-scheme\" content=\"dark\">" (icon-links)
     "<title>Operational logs · Alpha Compose</title>"
     "<style>"
     ":root{font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;line-height:1.45}"
     "*{box-sizing:border-box}body{margin:0}.shell{max-width:78rem;margin:0 auto;padding:2rem 1.25rem 4rem}"
     "header{display:flex;justify-content:space-between;gap:1rem;align-items:end;margin:1rem 0 2rem}"
     "h1,h2,p{margin-top:0}h1{font-size:clamp(2rem,4vw,3.4rem);letter-spacing:-.05em;margin-bottom:.35rem}"
     ".muted{color:var(--color-muted)}.eyebrow{color:var(--color-accent);font-size:.75rem;font-weight:800;letter-spacing:.12em;text-transform:uppercase}"
     ".card{background:var(--color-surface);border:1px solid var(--color-border);border-radius:1.1rem;box-shadow:var(--shadow-surface);padding:1.35rem;margin:1rem 0}"
     ".filters{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:1rem;align-items:end}label{display:block;font-weight:700;font-size:.9rem}input,select{font:inherit;width:100%;border:1px solid #6b8ba5;border-radius:.65rem;background:#06182b;color:var(--color-text);padding:.68rem .75rem;margin-top:.4rem}"
     "button,.button{border:1px solid var(--color-border);border-radius:.65rem;padding:.7rem 1rem;font-weight:800;cursor:pointer;background:var(--color-surface-soft);color:var(--color-text);text-decoration:none;display:inline-block}.primary{background:var(--color-accent);color:var(--color-accent-ink);box-shadow:0 .35rem .8rem #0fc3ff2e}"
     ".actions{display:flex;gap:.7rem;align-items:center;flex-wrap:wrap;margin-top:1rem}.log-entry{border-top:1px solid #e8edf4;padding:1rem 0}.log-entry:first-child{border-top:0;padding-top:0}.log-entry header{display:flex;justify-content:flex-start;align-items:center;gap:.65rem;margin:0 0 .5rem;flex-wrap:wrap}.log-entry time{color:#718097;font-size:.85rem}.log-level{border-radius:999px;background:#e8eef8;padding:.2rem .55rem;font-size:.75rem;font-weight:800}.log-entry code,pre,dt,dd{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}.log-message{font-weight:700}.log-entry dl{display:grid;grid-template-columns:max-content 1fr;gap:.25rem .9rem;margin:0}.log-entry dt{color:#718097}.log-entry dd{margin:0;overflow-wrap:anywhere}.log-entry pre{white-space:pre-wrap;overflow:auto;background:#f8fafc;border-radius:.65rem;padding:1rem;margin:0;font-size:.82rem}.empty{padding:2rem 0;text-align:center;color:#5c6b82}footer{margin-top:2rem;color:#6c7a90}footer a{color:#315b9d;margin-right:.75rem}@media(max-width:680px){.shell{padding:1rem .8rem 3rem}header{display:block}.filters{grid-template-columns:1fr}.log-entry dl{grid-template-columns:1fr}.log-entry dt{margin-top:.5rem}}"
     (theme-style)
     "</style></head><body data-theme=\"telemetry\"><div class=\"shell\"><header><div><div class=\"eyebrow\">Administration</div><h1>Operational logs</h1><p class=\"muted\">Safe structured events retained for 30 days. Showing up to 100 recent entries.</p></div><p class=\"muted\">Signed in as "
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

(defn job-fragment
  [{:keys [id state attempt failureCode stage status retryable elapsedMs timeoutMs
           output]
    :as job}]
  (let [path (str "/ui/jobs/" id)
        polling? (contains? #{"queued" "launching" "running"
                              "cancellation-requested"}
                            state)
        cancellable? (contains? #{"queued" "launching" "running"} state)
        retryable? (jobs/retry-eligible? job)
        drive-link (:driveWebViewLink output)]
    (str "<article id=\"job-" (escape-html id) "\" class=\"job\" data-job-state=\""
         (escape-html state) "\""
         (when polling?
           (str " hx-get=\"" (escape-html path)
                "\" hx-trigger=\"load delay:2s\" hx-swap=\"outerHTML\""))
         "><h2>" (escape-html (title-case state))
         "</h2><p>Attempt " (long attempt) "</p>"
         (when failureCode
           (str "<p>Failure: " (escape-html failureCode) "</p>"))
         (when stage
           (str "<p>Stage: " (escape-html (title-case stage)) "</p>"))
         (when (some? status)
           (str "<p>Status: " (long status) "</p>"))
         (when (some? retryable)
           (str "<p>Retryable: " (if retryable "yes" "no") "</p>"))
         (when (some? elapsedMs)
           (str "<p>Elapsed: " (long elapsedMs) " ms</p>"))
         (when (some? timeoutMs)
           (str "<p>Deadline: " (long timeoutMs) " ms</p>"))
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

(defn preview-failure-fragment
  [{:keys [status failureCode timeoutMs retryable field expectedSchema
           documentationPath]}]
  (let [input-label (case field
                      "telemetry" "Heart-rate telemetry"
                      "spo2.telemetry" "SpO₂ telemetry"
                      "telemetryFormat" "Telemetry format"
                      "spo2" "SpO₂ input"
                      "Telemetry input")
        correction
        (case failureCode
          "unsupported_telemetry_columns"
          "Use timestamped Polar CSV or the documented columns for the selected telemetry format. Summary-only exports are not supported."
          "malformed_telemetry_row"
          "Correct the malformed row, keeping an absolute timestamp and numeric value in every required column."
          "heart_rate_out_of_range"
          "Heart rate must be between 20 and 260 bpm."
          "telemetry_value_out_of_range"
          "Correct the value so it is inside the documented range."
          "unordered_telemetry"
          "Sort telemetry timestamps in strictly increasing order and remove duplicates."
          "insufficient_telemetry_coverage"
          "Provide at least two samples that cover the full requested section."
          "telemetry_too_large"
          "Reduce the telemetry input to the documented size limit."
          "telemetry_sample_limit_exceeded"
          "Use fewer telemetry samples while preserving coverage of the requested section."
          "unsupported_telemetry_format"
          "Choose Polar CSV, Garmin FIT, or OxiWear heart-rate CSV and provide content matching that format."
          "invalid_telemetry"
          "Provide telemetry content matching the selected format."
          "Review the telemetry input and API documentation before retrying.")
        expected-columns
        (when (and (map? expectedSchema)
                   (seq (:timestampColumns expectedSchema))
                   (seq (:valueColumns expectedSchema)))
          (str "<p>Accepted timestamp columns: "
               (str/join ", "
                         (map #(str "<code>" (escape-html %) "</code>")
                              (:timestampColumns expectedSchema)))
               ". Accepted value columns: "
               (str/join ", "
                         (map #(str "<code>" (escape-html %) "</code>")
                              (:valueColumns expectedSchema)))
               ".</p>"))
        documentation-link
        (when (and (string? documentationPath)
                   (str/starts-with? documentationPath "/openapi.yaml"))
          (str "<p><a href=\"" (escape-html documentationPath)
               "\">Review the telemetry request contract</a>.</p>"))]
    (str "<article id=\"preview-result\" class=\"preview-error\" role=\"alert\"><h2>Preview failed</h2>"
         (if (or timeoutMs (= 504 status))
           "<p>Preview did not finish.</p>"
           "<p>Preview could not start.</p>")
         (when failureCode
           (str "<section><h3>" (escape-html input-label) "</h3><p>"
                (escape-html correction) "</p>" expected-columns
                documentation-link "</section>"))
         "<p>No durable render was submitted. If a Preview started, its reservation remains counted.</p>"
         (if retryable
           "<p>Retry with the Preview button when ready.</p>"
           "<p>Review the request before starting another preview.</p>")
         "</article>")))

(defn- source-duration-warning-copy
  [{:keys [requestedMomentCount generatedMomentCount omittedMomentCount
           requestedDurationSeconds]}]
  (str "We generated " (long generatedMomentCount) " of "
       (long requestedMomentCount) " preview frames. The selected video ends "
       "before the " (long requestedDurationSeconds) "-second section, so "
       (long omittedMomentCount) " later preview "
       (if (= 1 omittedMomentCount) "frame is" "frames are")
       " unavailable. Shorten the section or choose a longer video."))

(defn- zero-source-duration-copy
  [{:keys [requestedMomentCount requestedDurationSeconds]}]
  (str "We could not generate any of the " (long requestedMomentCount)
       " preview frames. The selected video ends before the "
       (long requestedDurationSeconds)
       "-second section. Shorten the section or choose a longer video."))

(defn preview-operation-fragment
  [{:keys [id state progressPercent error result]} generation]
  (let [path (str "/ui/previews/" id "?generation="
                  (url-value generation))
        source-duration-warning
        (first (filter #(= "source_duration_too_short" (:reason %))
                       (:warnings result)))]
    (cond
      (= "succeeded" state)
      (if (seq (:sections result))
        (let [assets (into {} (map (juxt :id identity)) (:assets result))
              image-button
              (fn [reference title alt checkerboard?]
                (str "<button type=\"button\" class=\"preview-open\" data-full=\""
                     (escape-html (:fullUrl reference)) "\" data-title=\""
                     (escape-html title) "\" data-alt=\""
                     (escape-html alt) "\" aria-label=\"Open larger image: "
                     (escape-html title) "\"><img loading=\"eager\" src=\""
                     (escape-html (:thumbnailUrl reference)) "\" alt=\""
                     (escape-html alt) "\""
                     (when checkerboard? " class=\"checkerboard\"")
                     "></button>"))
              moment-html
              (fn [section moment]
                (let [asset (get assets (:frameRef moment))
                      title (:title moment)
                      subject (str (:name section) ", " title)
                      photo
                      (fn [role reference alt checkerboard? class-name]
                        (str "<div class=\"preview-cell" class-name
                             "\"><h3 class=\"photo-title\"><span class=\"frame-role\">"
                             (escape-html role) "</span> - "
                             (escape-html title) "</h3>"
                             (image-button reference (str role " - " title)
                                           alt checkerboard?)
                             "</div>"))]
                  (str "<article class=\"preview-moment\">"
                       (case (:kind asset)
                         "overlay"
                         (photo "Overlay" (:image asset)
                                (str subject ", transparent overlay")
                                true " overlay-cell")
                         "final"
                         (photo "Final" (:image asset)
                                (str subject ", final composited frame")
                                false " final-cell"))
                       "</article>")))]
          (str "<article id=\"preview-result\" class=\"preview-gallery\" data-preview-operation=\""
               (escape-html id) "\" data-preview-generation=\""
               (escape-html generation) "\" aria-live=\"polite\"><header><h2>Preview ready</h2>"
               "<p class=\"muted\">Key moments are ordered on the exact 25 fps output timeline.</p></header>"
               (when source-duration-warning
                 (str "<aside class=\"preview-warning\" role=\"status\"><h3>Some preview frames are unavailable</h3><p>"
                      (escape-html
                       (source-duration-warning-copy source-duration-warning))
                      "</p></aside>"))
               (apply str
                      (for [section (:sections result)]
                        (str "<section class=\"trace-preview\" aria-labelledby=\"trace-"
                             (escape-html (:id section)) "\"><h2 id=\"trace-"
                             (escape-html (:id section)) "\">"
                             (escape-html (:name section)) "</h2>"
                             "<div class=\"preview-scroll\"><div class=\"preview-moments\">"
                             (apply str (map #(moment-html section %)
                                             (:moments section)))
                             "</div></div></section>")))
               "<dialog id=\"preview-dialog\" tabindex=\"-1\" aria-labelledby=\"preview-dialog-title\" aria-describedby=\"preview-dialog-counter\"><div class=\"dialog-head\"><h2 id=\"preview-dialog-title\">Preview image</h2><button type=\"button\" class=\"preview-dialog-close\" aria-label=\"Close full-image viewer\">Close</button></div><div class=\"dialog-image-frame\"><img alt=\"\"></div><div class=\"dialog-nav\"><button type=\"button\" class=\"preview-previous\" aria-label=\"Previous image\">Previous</button><p id=\"preview-dialog-counter\" class=\"preview-counter\" aria-live=\"polite\"></p><button type=\"button\" class=\"preview-next\" aria-label=\"Next image\">Next</button></div></dialog>"
               "</article>"))
        (str "<article id=\"preview-result\" class=\"preview-empty\" role=\"status\" data-preview-operation=\""
             (escape-html id) "\" data-preview-generation=\""
             (escape-html generation) "\"><h2>No preview moments</h2><p>The normalized traces contain no renderable frames.</p></article>"))

      (contains? #{"failed" "cancelled"} state)
      (str "<article id=\"preview-result\" class=\"preview-error\" role=\"alert\" data-preview-operation=\""
           (escape-html id) "\" data-preview-generation=\""
           (escape-html generation) "\">"
           (cond
             (= "cancelled" state)
             "<h2>Preview cancelled</h2><p>The preview operation was cancelled.</p>"

             (= "source_duration_too_short" (:reason error))
             (str "<h2>The selected video is too short</h2><p>"
                  (escape-html (zero-source-duration-copy error)) "</p>")

             :else
             (str "<h2>Preview did not finish</h2>"
                  "<p>We could not generate this preview. Check the selected "
                  "video and inputs, then retry with the Preview button.</p>"))
           "<p>No durable render was submitted. The Preview reservation remains counted.</p>"
           "</article>")

      :else
      (str "<article id=\"preview-result\" class=\"preview-pending\" role=\"status\" aria-live=\"polite\" data-preview-operation=\""
           (escape-html id) "\" data-preview-generation=\""
           (escape-html generation) "\" hx-get=\"" (escape-html path)
           "\" hx-trigger=\"load delay:1s\" hx-swap=\"outerHTML\" hx-request='{\"timeout\":15000}'><h2>Preparing preview</h2><p>"
           (escape-html (title-case state)) " - " (long progressPercent)
           "%</p><progress max=\"100\" value=\"" (long progressPercent)
           "\">" (long progressPercent) "%</progress></article>"))))

(defn preview-stale-fragment [generation]
  (str "<article id=\"preview-result\" class=\"preview-stale\" role=\"status\" data-preview-generation=\""
       (escape-html generation) "\"><h2>Preview expired</h2><p>Start a new preview to refresh these images.</p></article>"))

(defn page [{:keys [user csrf picker-config tokens members logs-enabled?]}]
  (let [csrf-headers (escape-html
                      (str "{\"X-CSRF-Token\":\"" csrf "\"}"))]
    (str
     "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
     "<meta name=\"color-scheme\" content=\"dark\">" (icon-links)
     "<title>Alpha Compose</title>"
     "<script src=\"https://cdn.jsdelivr.net/npm/htmx.org@2.0.10/dist/htmx.min.js\" "
     "integrity=\"sha384-H5SrcfygHmAuTDZphMHqBJLc3FhssKjG7w/CeCpFReSfwBWDTKpkzPP8c+cLsK+V\" "
     "crossorigin=\"anonymous\"></script>"
     (when picker-config
       "<script src=\"https://apis.google.com/js/api.js\"></script>")
     "<style>"
     ":root{font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;line-height:1.45}"
     "*{box-sizing:border-box}body{margin:0}.shell{max-width:78rem;margin:0 auto;padding:2rem 1.25rem 4rem}"
     "header{display:flex;justify-content:space-between;gap:1rem;align-items:end;margin:1rem 0 2rem}"
     "h1,h2,h3,p{margin-top:0}h1{font-size:clamp(2rem,4vw,3.4rem);letter-spacing:-.05em;margin-bottom:.35rem}h2{font-size:1.25rem;margin-bottom:.35rem}"
     ".muted,.hint{color:var(--color-muted)}.eyebrow{color:var(--color-accent);font-size:.75rem;font-weight:800;letter-spacing:.12em;text-transform:uppercase}.session-controls{display:flex;align-items:flex-end;flex-direction:column;gap:.65rem}.session-controls p,.session-controls form{margin:0}"
     ".card{background:var(--color-surface);border:1px solid var(--color-border);border-radius:1.1rem;box-shadow:var(--shadow-surface);padding:1.35rem;margin:1rem 0}"
     ".drive-card{display:flex;align-items:center;justify-content:space-between;gap:1rem;background:var(--color-surface-strong);color:var(--color-text);border:1px solid var(--color-border-strong)}.drive-card .muted{color:#c8ddef}"
     ".drive-card a{color:var(--color-link)}.drive-actions{display:flex;align-items:center;gap:.8rem;flex-wrap:wrap}"
     ".section-head{display:flex;justify-content:space-between;align-items:start;gap:1rem;margin-bottom:1rem}.step{color:var(--color-subtle);font-weight:800;font-size:.8rem}"
     ".field-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1rem}.full{grid-column:1/-1}label,.field-label{display:block;font-weight:700;font-size:.9rem}"
     "label small,.field-label small{display:block;color:var(--color-muted);font-weight:400;margin-top:.2rem}input,select,textarea{font:inherit;width:100%;border:1px solid #6b8ba5;border-radius:.65rem;background:#06182b;color:var(--color-text);padding:.68rem .75rem;margin-top:.4rem}"
     "input:focus,select:focus,textarea:focus{outline:3px solid var(--color-warning);border-color:var(--color-warning)}input[type=file]{padding:.5rem;background:#06182b}"
     "textarea{min-height:8rem;resize:vertical;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.82rem}"
     ".source-box{background:#06182b;border:1px dashed var(--color-border-strong);border-radius:.8rem;padding:1rem}.source-box textarea{background:#06182b}"
     ".optional{border-top:1px solid var(--color-border);margin-top:1.25rem;padding-top:1.25rem}.toggle{display:flex;align-items:center;gap:.65rem;font-weight:700}.toggle input{width:auto;margin:0}"
     ".actions{display:flex;align-items:center;gap:.75rem;flex-wrap:wrap;margin-top:1.25rem}.button,button{border:1px solid var(--color-border);border-radius:.65rem;padding:.7rem 1rem;font-weight:800;cursor:pointer;background:var(--color-surface-soft);color:var(--color-text)}"
     ".button.primary,button.primary{background:var(--color-accent);color:var(--color-accent-ink);box-shadow:0 .35rem .8rem #0fc3ff2e}.button:hover,button:hover{filter:brightness(1.08)}"
     ".button.primary:disabled,button.primary:disabled,.button.primary:disabled:hover,button.primary:disabled:hover{background:#53677b;color:#d2dce6;box-shadow:none;cursor:not-allowed;filter:none}"
     ".button-with-spinner{display:inline-flex;align-items:center;justify-content:center;gap:.5rem}.button-spinner{display:inline-block;width:1rem;height:1rem;border:.15rem solid currentColor;border-right-color:transparent;border-radius:50%;animation:preview-button-spin .75s linear infinite;flex:0 0 auto}.button-spinner[hidden]{display:none}@keyframes preview-button-spin{to{transform:rotate(360deg)}}@media(prefers-reduced-motion:reduce){.button-spinner{animation:none}}"
     ".status{min-height:1.4rem;color:var(--color-muted);font-size:.9rem}.status.error{color:var(--color-danger)}.status.success{color:var(--color-success)}"
     "details summary{cursor:pointer;font-weight:800;color:var(--color-link)}.raw-panel textarea{min-height:18rem}.raw-actions{display:flex;gap:.65rem;flex-wrap:wrap;margin-top:.7rem}.json-errors{white-space:pre-line}.field-reference{margin:.75rem 0 0;padding-left:1.25rem}.field-reference li{margin:.35rem 0}.field-reference code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}"
     ".results{display:block;min-width:0}.results img{max-width:100%;border:1px solid #d9e1ed;border-radius:.75rem;background:#eef2f7}.preview-gallery{min-width:0}.preview-warning{background:#fff8e8;border:1px solid #e7c46b;border-radius:.8rem;color:#59400a;padding:.85rem 1rem;margin:1rem 0}.preview-warning h3,.preview-warning p{margin:0}.preview-warning p{margin-top:.35rem}.trace-preview{background:white;border:1px solid #e1e7f0;border-radius:1rem;padding:1rem;margin:1rem 0;min-width:0;max-width:100%}.preview-scroll{max-width:100%;min-width:0;overflow:visible}.preview-moments{display:flex;flex-wrap:wrap;justify-content:center;gap:.8rem;align-items:flex-start;min-width:0;max-width:100%}.preview-moment{display:flex;flex:0 0 8rem;width:8rem;flex-direction:column;gap:.65rem;min-width:0}.photo-title{font-size:.75rem;line-height:1.35;overflow-wrap:anywhere;margin:0 0 .35rem}.preview-cell{min-width:0;width:100%}.preview-cell .preview-open{display:block;width:100%;padding:0;background:#f8fafc}.preview-cell img{display:block;width:100%;height:auto}.frame-role{display:inline;font-weight:800;letter-spacing:.04em}.checkerboard{background-color:#fff;background-image:linear-gradient(45deg,#d9e1ed 25%,transparent 25%),linear-gradient(-45deg,#d9e1ed 25%,transparent 25%),linear-gradient(45deg,transparent 75%,#d9e1ed 75%),linear-gradient(-45deg,transparent 75%,#d9e1ed 75%);background-size:20px 20px;background-position:0 0,0 10px,10px -10px,-10px 0}.preview-pending,.preview-error,.preview-stale,.preview-empty{background:white;border:1px solid #e1e7f0;border-radius:1rem;padding:1rem;margin:1rem 0}.preview-pending progress{width:min(24rem,100%)}#preview-dialog{width:calc(100dvw - 1rem);height:calc(100dvh - 1rem);max-width:none;max-height:none;border:0;border-radius:1rem;padding:1rem;overflow:hidden}#preview-dialog[open]{display:grid;grid-template-rows:auto minmax(0,1fr) auto;gap:.75rem}#preview-dialog::backdrop{background:#10213acc}.dialog-image-frame{display:flex;align-items:center;justify-content:center;min-width:0;min-height:0;overflow:hidden}#preview-dialog img{display:block;width:100%;height:100%;min-width:0;min-height:0;object-fit:contain;margin:0}.dialog-head{display:flex;justify-content:space-between;align-items:center;gap:1rem;min-width:0}.dialog-head h2{margin:0;min-width:0;overflow-wrap:anywhere}.dialog-nav{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:.75rem}.preview-counter{margin:0;text-align:center}"
     ".job{margin:0}.notice{border:2px solid #d8a94d;padding:1rem;overflow-wrap:anywhere}.notice code{display:block;margin-top:.6rem;white-space:pre-wrap}"
     ".inline{display:inline}footer{margin-top:2rem;color:#6c7a90}footer a{color:#315b9d;margin-right:.75rem}"
     "@media(max-width:680px){.shell{padding:1rem .8rem 3rem}header,.drive-card,.section-head{display:block}.session-controls{align-items:flex-start;margin-top:1rem}.field-grid{grid-template-columns:1fr}.drive-actions{margin-top:1rem}.preview-moment{flex:0 1 24rem;width:min(100%,24rem);border-top:1px solid #e1e7f0;padding:1rem 0}.preview-moment:first-child{border-top:0;padding-top:0}.preview-cell{width:100%;margin-top:.75rem}}"
     (theme-style)
     "</style>"
     "</head><body data-theme=\"telemetry\" hx-headers=\"" csrf-headers "\">"
     "<div class=\"shell\"><header><div><div class=\"eyebrow\">Telemetry overlays for video</div><h1>Compose your overlay</h1><p class=\"muted\">Configure a render, preview it, then send the finished overlay to Drive. Finished renders use durable jobs, including full-length sections.</p></div>"
     "<div class=\"session-controls\"><p class=\"muted\">Signed in as "
     (escape-html (:email user))
     "</p><form method=\"post\" action=\"/v1/auth/logout\"><input type=\"hidden\" name=\"csrf\" value=\""
     (escape-html csrf)
     "\"><button type=\"submit\">Log out</button></form></div></header>"
     "<section class=\"card drive-card\"><div><h2>Google Drive</h2><p class=\"muted\">Pick a supported video from My Drive, files shared with you, or a Shared Drive. Video results are filtered; folders are only for navigation. You can also upload a source video. Selection grants Alpha Compose access to that file only. Every finished render still goes to your Alpha Compose folder in My Drive.</p></div><div class=\"drive-actions\"><button id=\"open-picker\" type=\"button\">Pick one video</button><span>Selected: <output id=\"picker-selection\">None</output></span></div></section>"
     "<section class=\"card\"><div class=\"section-head\"><div><h2>Optional source video</h2><p class=\"muted\">The browser sends only the selected ID. The server verifies its MIME type, 2 GiB limit, download access, decodability, and duration.</p></div></div><input id=\"source-video-file-id\" type=\"hidden\"><div class=\"field-grid\"><label>Output<select id=\"output-format\"><option value=\"h264-mp4\">H.264 MP4</option><option value=\"prores-422-mov\">ProRes 422 MOV</option></select></label><label>Fit<select id=\"fit-mode\"><option value=\"letterbox\">Letterbox / pillarbox</option><option value=\"crop\">Crop to fill</option></select></label><label>Audio<select id=\"audio-mode\"><option value=\"source+heartbeat\">Source + heartbeat</option><option value=\"source-only\">Source only</option><option value=\"heartbeat-only\">Heartbeat only</option></select></label></div></section>"
     "<form id=\"render-form\" hx-post=\"/ui/jobs\" hx-target=\"#job-result\" hx-swap=\"innerHTML\">"
     "<input id=\"render-request\" type=\"hidden\" name=\"request\" value=\"{}\">"
     "<section class=\"card\"><div class=\"section-head\"><div><div class=\"step\">Step 1</div><h2>Choose your data</h2><p class=\"muted\">Select a supported telemetry format, then upload a file or paste its contents.</p></div></div>"
     "<div class=\"field-grid\"><label>Telemetry format<select id=\"telemetry-format\" required><option value=\"polar-csv\">Polar CSV</option><option value=\"garmin-fit\">Garmin FIT</option><option value=\"oxiwear-hr-csv\">OxiWear heart-rate CSV</option></select></label>"
     "<label>Render preset<select id=\"preset\" required><option value=\"1080p25\">1080p · 25 fps · up to 8 minutes</option><option value=\"2.7k25\">2.7K · 25 fps · up to 4 minutes</option></select></label>"
     "<div class=\"source-box full\"><label for=\"telemetry-file\">Telemetry file <small>CSV or FIT files are read locally in your browser.</small></label><input id=\"telemetry-file\" type=\"file\" accept=\".csv,text/csv\"><label for=\"telemetry\" style=\"margin-top:1rem\">Or paste telemetry content</label><textarea id=\"telemetry\" placeholder=\"Paste CSV text, or base64-encoded FIT content\" required></textarea><p id=\"telemetry-status\" class=\"status\" role=\"status\"></p></div></div></section>"
     "<section class=\"card\"><div class=\"section-head\"><div><div class=\"step\">Step 2</div><h2>Line up the timeline</h2><p class=\"muted\">Use the same timezone for camera, section, and timer entry. Telemetry timestamps stay as provided by the file. The selected IANA zone also controls the rendered local video clock.</p></div></div>"
     "<div class=\"field-grid\"><label>Timezone<select id=\"timezone\"><option value=\"local\">My browser timezone</option><option value=\"UTC\">UTC</option><option value=\"Europe/Warsaw\">Europe/Warsaw</option><option value=\"Europe/London\">Europe/London</option><option value=\"America/New_York\">America/New_York</option><option value=\"America/Los_Angeles\">America/Los_Angeles</option><option value=\"Asia/Tokyo\">Asia/Tokyo</option><option value=\"Australia/Sydney\">Australia/Sydney</option></select><small>Submitted as required <code>displayTimeZone</code>; local timestamps are converted to absolute instants.</small></label>"
     "<label>Telemetry sync time<input id=\"telemetry-sync-at\" type=\"datetime-local\" step=\"1\" required><small>Timestamp in the telemetry file at the camera sync moment.</small></label>"
     "<label>Camera sync time<input id=\"camera-sync-at\" type=\"datetime-local\" step=\"1\" required></label>"
     "<label>Section start<input id=\"section-start-at\" type=\"datetime-local\" step=\"1\" required></label>"
     "<label>Section end<input id=\"section-end-at\" type=\"datetime-local\" step=\"1\" required></label></div></section>"
     "<section class=\"card\"><div class=\"section-head\"><div><div class=\"step\">Step 3</div><h2>Optional overlays</h2><p class=\"muted\">Add supporting data only when it is present in this render.</p></div></div>"
     "<div class=\"field-grid\"><label>Future trace opacity (%)<input id=\"future-trace-opacity-percent\" type=\"number\" min=\"0\" max=\"100\" step=\"any\" value=\"25\" required><small>Opacity of the not-yet-reached heart-rate trace. Default: 25%.</small></label></div>"
     "<div class=\"optional\"><label class=\"toggle\"><input id=\"spo2-enabled\" type=\"checkbox\"> Include OxiWear SpO₂</label><div id=\"spo2-fields\" hidden class=\"source-box\"><label for=\"spo2-file\">SpO₂ CSV file</label><input id=\"spo2-file\" type=\"file\" accept=\".csv,text/csv\"><label for=\"spo2-telemetry\" style=\"margin-top:1rem\">Or paste SpO₂ CSV</label><textarea id=\"spo2-telemetry\" placeholder=\"reading_time,spo2\n2026-07-17T10:00:00Z,97\"></textarea><p id=\"spo2-status\" class=\"status\" role=\"status\"></p></div></div>"
     "<div class=\"optional\"><label class=\"toggle\"><input id=\"timer-enabled\" type=\"checkbox\"> Show elapsed timer</label><div id=\"timer-fields\" hidden class=\"field-grid\"><label>Timer start<input id=\"timer-start-at\" type=\"datetime-local\" step=\".001\"></label><label>Timer end<input id=\"timer-end-at\" type=\"datetime-local\" step=\".001\"></label></div></div>"
     "<div class=\"optional\"><label class=\"toggle\"><input id=\"watermark-enabled\" type=\"checkbox\"> Add a PNG watermark</label><div id=\"watermark-fields\" hidden class=\"source-box\"><label for=\"watermark-file\">PNG file <small>It is converted to base64 locally and sent with this request.</small></label><input id=\"watermark-file\" type=\"file\" accept=\"image/png,.png\"><p id=\"watermark-status\" class=\"status\" role=\"status\"></p></div></div></section>"
     "<section class=\"card raw-panel\"><details><summary>Advanced: paste or inspect raw JSON</summary><p class=\"hint\">Paste a request and choose “Apply to form”. The JSON is checked for structural errors first; form edits are reflected here before preview or submission.</p><p class=\"hint\"><strong>Accepted fields</strong></p><ul class=\"field-reference\"><li><code>telemetryFormat</code> and <code>telemetry</code> are required. Formats: <code>polar-csv</code>, <code>garmin-fit</code> (base64 FIT), or <code>oxiwear-hr-csv</code>.</li><li><code>preset</code> is required: <code>1080p25</code> (1920×1080, 25 fps, up to 8 minutes) or <code>2.7k25</code> (2704×1520, 25 fps, up to 4 minutes).</li><li><code>displayTimeZone</code> is required: a known IANA timezone such as <code>Europe/Warsaw</code> or <code>UTC</code>; it controls only the rendered local clock.</li><li><code>telemetrySyncAt</code>, <code>cameraSyncAt</code>, <code>sectionStartAt</code>, and <code>sectionEndAt</code> are required ISO-8601 instants with <code>Z</code> or an explicit UTC offset.</li><li><code>futureTraceOpacityPercent</code> is optional: a number from <code>0</code> through <code>100</code>, in percent; default <code>25</code>.</li><li><code>spo2</code> is optional: <code>{format:\"oxiwear-spo2-csv\", telemetry}</code>.</li><li><code>timer</code> is optional: <code>{startAt, endAt}</code>, within the requested section.</li><li><code>watermark</code> is optional: <code>{contentBase64}</code>, a bounded PNG encoded as base64.</li><li><code>sourceVideo</code> is optional: <code>{fileId}</code>; when present, <code>outputFormat</code> (<code>h264-mp4</code> or <code>prores-422-mov</code>), <code>fitMode</code> (<code>letterbox</code>, <code>pillarbox</code>, or <code>crop</code>), and <code>audioMode</code> (<code>source+heartbeat</code>, <code>source-only</code>, or <code>heartbeat-only</code>) configure compositing.</li></ul><textarea id=\"raw-json\" spellcheck=\"false\">{}</textarea><div class=\"raw-actions\"><button id=\"apply-json\" type=\"button\">Apply JSON to form</button><button id=\"copy-json\" type=\"button\">Copy generated JSON</button></div><p id=\"json-status\" class=\"status json-errors\" role=\"status\"></p></details></section>"
     "<section class=\"card\">"
     (preview-admission-disclosure)
     "<div class=\"actions\"><button id=\"preview-button\" class=\"primary button-with-spinner\" type=\"button\" hx-post=\"/ui/preview\" hx-include=\"closest form\" hx-target=\"#preview-result\" hx-swap=\"outerHTML\" hx-request='{\"timeout\":15000}'><span class=\"button-spinner\" aria-hidden=\"true\" hidden></span><span>Preview overlay</span></button><button id=\"submit-button\" class=\"primary\" type=\"submit\">Submit render</button><span id=\"preview-submit-status\" class=\"status\" role=\"status\">Preview is optional. Submit when ready.</span><span id=\"form-status\" class=\"status\" role=\"status\"></span></div></section></form>"
     "<div class=\"results\"><div id=\"preview-result\"></div><div id=\"job-result\"></div></div>"
     (token-panel tokens)
     (when (admin/administrator? (:role user))
       (member-panel members logs-enabled?))
     "<footer><a href=\"/privacy\">Privacy</a> · <a href=\"/terms\">Terms</a></footer>"
     "<script>(function(){"
     "const form=document.getElementById('render-form'), hidden=document.getElementById('render-request'), raw=document.getElementById('raw-json');"
     "const status=document.getElementById('form-status'), jsonStatus=document.getElementById('json-status'),submitButton=document.getElementById('submit-button'),submitStatus=document.getElementById('preview-submit-status');"
     "const byId=id=>document.getElementById(id), value=id=>byId(id).value.trim();"
     "function show(node,message,kind){node.textContent=message;node.className='status'+(kind?' '+kind:'');}"
     "function activeZone(){const selected=value('timezone');return selected==='local'?Intl.DateTimeFormat().resolvedOptions().timeZone:selected;}function validTimeZone(zone){if(typeof zone!=='string'||!zone.trim()||/^(?:Z|[+-]\\d{2}(?::?\\d{2})?)$/.test(zone))return false;try{new Intl.DateTimeFormat('en-US',{timeZone:zone}).format(0);return true;}catch(_error){return false;}}function setDisplayTimeZone(zone){const selector=byId('timezone'),custom=[...selector.options].find(option=>option.dataset.customTimeZone==='true');if(custom&&custom.value!==zone)custom.remove();const exact=[...selector.options].find(option=>option.value===zone);if(exact){selector.value=zone;return;}if(zone===Intl.DateTimeFormat().resolvedOptions().timeZone){selector.value='local';return;}const option=document.createElement('option');option.value=zone;option.textContent=zone;option.dataset.customTimeZone='true';selector.append(option);selector.value=zone;}"
     "function dateParts(instant,zone){const parts=new Intl.DateTimeFormat('en-US',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(new Date(instant));return Object.fromEntries(parts.filter(part=>part.type!=='literal').map(part=>[part.type,Number(part.value)]));}"
     "function localToIso(input){const text=value(input);if(!text)throw new Error(input+' is required');const [date,time]=text.split('T'),dateValues=date.split('-').map(Number),timeValues=time.split(':');if(dateValues.length!==3||timeValues.length<2)throw new Error('Enter a valid date and time');const seconds=Number(timeValues[2]||0),wholeSeconds=Math.trunc(seconds),milliseconds=Math.round((seconds-wholeSeconds)*1000),target=Date.UTC(dateValues[0],dateValues[1]-1,dateValues[2],Number(timeValues[0]),Number(timeValues[1]),wholeSeconds)+milliseconds;let guess=target;for(let i=0;i<4;i++){const parts=dateParts(guess,activeZone()),shown=Date.UTC(parts.year,parts.month-1,parts.day,parts.hour,parts.minute,parts.second)+milliseconds;guess+=target-shown;}const result=new Date(guess);if(Number.isNaN(result.getTime()))throw new Error('Enter a valid date and time');return result.toISOString();}"
     "function isoToLocal(instant){const date=new Date(instant);if(Number.isNaN(date.getTime()))throw new Error('Invalid ISO-8601 timestamp: '+instant);const parts=dateParts(date.getTime(),activeZone()),milliseconds=date.getUTCMilliseconds(),fraction=milliseconds?'.'+String(milliseconds).padStart(3,'0'):'';return [parts.year,String(parts.month).padStart(2,'0'),String(parts.day).padStart(2,'0')].join('-')+'T'+[String(parts.hour).padStart(2,'0'),String(parts.minute).padStart(2,'0'),String(parts.second).padStart(2,'0')].join(':')+fraction;}"
     "const fileBackedValues=Object.create(null);function contentValue(id){return Object.prototype.hasOwnProperty.call(fileBackedValues,id)?String(fileBackedValues[id]).trim():value(id);}function setFileBackedValue(id,content){fileBackedValues[id]=content;byId(id).value=content;invalidatePreview();}function clearFileBackedValue(id){delete fileBackedValues[id];}"
     "function required(id,label){const result=contentValue(id);if(!result)throw new Error(label+' is required');return result;}"
     "function boundedNumber(id,label,minimum,maximum){const text=value(id),result=Number(text);if(!text||!Number.isFinite(result)||result<minimum||result>maximum)throw new Error(label+' must be a number from '+minimum+' through '+maximum);return result;}"
     "function buildRequest(){const request={telemetryFormat:required('telemetry-format','Telemetry format'),telemetry:required('telemetry','Telemetry'),preset:required('preset','Render preset'),displayTimeZone:activeZone(),futureTraceOpacityPercent:boundedNumber('future-trace-opacity-percent','Future trace opacity',0,100),telemetrySyncAt:localToIso('telemetry-sync-at'),cameraSyncAt:localToIso('camera-sync-at'),sectionStartAt:localToIso('section-start-at'),sectionEndAt:localToIso('section-end-at')};const source=value('source-video-file-id');if(source){request.sourceVideo={fileId:source};request.outputFormat=value('output-format');request.fitMode=value('fit-mode');request.audioMode=value('audio-mode');}if(byId('spo2-enabled').checked){request.spo2={format:'oxiwear-spo2-csv',telemetry:required('spo2-telemetry','SpO₂ telemetry')};}if(byId('timer-enabled').checked){request.timer={startAt:localToIso('timer-start-at'),endAt:localToIso('timer-end-at')};}if(byId('watermark-enabled').checked){request.watermark={contentBase64:required('watermark-content','Watermark file')};}return request;}"
     "const requestFields=['telemetryFormat','telemetry','preset','displayTimeZone','futureTraceOpacityPercent','telemetrySyncAt','cameraSyncAt','sectionStartAt','sectionEndAt','spo2','timer','watermark','sourceVideo','outputFormat','fitMode','audioMode'],requiredRequestFields=['telemetryFormat','telemetry','preset','displayTimeZone','telemetrySyncAt','cameraSyncAt','sectionStartAt','sectionEndAt'],isoPattern=/^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})$/;"
     "function isObject(candidate){return candidate!==null&&typeof candidate==='object'&&!Array.isArray(candidate);}function has(candidate,key){return Object.prototype.hasOwnProperty.call(candidate,key);}function utf8Length(text){return new TextEncoder().encode(text).length;}function instantValue(value){return typeof value==='string'&&isoPattern.test(value)&&!Number.isNaN(Date.parse(value));}function unknownFields(candidate,allowed,path,errors){Object.keys(candidate).filter(key=>!allowed.includes(key)).forEach(key=>errors.push(path+' contains unknown field '+key+'.'));}function requiredString(candidate,key,path,errors){if(!has(candidate,key)||candidate[key]===''){errors.push(path+'.'+key+' is required.');return false;}if(typeof candidate[key]!=='string'){errors.push(path+'.'+key+' must be a string.');return false;}return true;}function validateInstant(candidate,key,path,errors){if(!requiredString(candidate,key,path,errors))return false;if(!instantValue(candidate[key])){errors.push(path+'.'+key+' must be an ISO-8601 instant with Z or an explicit UTC offset.');return false;}return true;}"
     "function validateRequest(request){const errors=[];if(!isObject(request))return ['Request must be a JSON object.'];unknownFields(request,requestFields,'Request',errors);requiredRequestFields.forEach(key=>requiredString(request,key,'Request',errors));if(typeof request.telemetryFormat==='string'&&request.telemetryFormat&&!['polar-csv','garmin-fit','oxiwear-hr-csv'].includes(request.telemetryFormat))errors.push('Request.telemetryFormat must be polar-csv, garmin-fit, or oxiwear-hr-csv.');if(typeof request.preset==='string'&&request.preset&&!['1080p25','2.7k25'].includes(request.preset))errors.push('Request.preset must be 1080p25 or 2.7k25.');if(has(request,'futureTraceOpacityPercent')&&(typeof request.futureTraceOpacityPercent!=='number'||!Number.isFinite(request.futureTraceOpacityPercent)||request.futureTraceOpacityPercent<0||request.futureTraceOpacityPercent>100))errors.push('Request.futureTraceOpacityPercent must be a number from 0 through 100.');if(typeof request.telemetry==='string'&&request.telemetry){const limit=request.telemetryFormat==='garmin-fit'?13981016:10485760;if(utf8Length(request.telemetry)>limit)errors.push('Request.telemetry exceeds its encoded size limit.');}['telemetrySyncAt','cameraSyncAt','sectionStartAt','sectionEndAt'].forEach(key=>{if(has(request,key)&&typeof request[key]==='string'&&request[key]&&!instantValue(request[key]))errors.push('Request.'+key+' must be an ISO-8601 instant with Z or an explicit UTC offset.');});const sectionTimes=['cameraSyncAt','sectionStartAt','sectionEndAt'].map(key=>Date.parse(request[key]));if(sectionTimes.every(time=>!Number.isNaN(time))&&!(sectionTimes[0]<=sectionTimes[1]&&sectionTimes[1]<sectionTimes[2]))errors.push('Request timestamps must satisfy cameraSyncAt <= sectionStartAt < sectionEndAt.');if(has(request,'spo2')){const value=request.spo2;if(!isObject(value))errors.push('Request.spo2 must be an object.');else{unknownFields(value,['format','telemetry'],'Request.spo2',errors);requiredString(value,'format','Request.spo2',errors);requiredString(value,'telemetry','Request.spo2',errors);if(typeof value.format==='string'&&value.format&&value.format!=='oxiwear-spo2-csv')errors.push('Request.spo2.format must be oxiwear-spo2-csv.');if(typeof value.telemetry==='string'&&value.telemetry&&utf8Length(value.telemetry)>10485760)errors.push('Request.spo2.telemetry exceeds the 10 MiB limit.');}}if(has(request,'timer')){const value=request.timer;if(!isObject(value))errors.push('Request.timer must be an object.');else{unknownFields(value,['startAt','endAt'],'Request.timer',errors);const startValid=validateInstant(value,'startAt','Request.timer',errors),endValid=validateInstant(value,'endAt','Request.timer',errors);if(startValid&&endValid){const start=Date.parse(value.startAt),end=Date.parse(value.endAt),sectionStart=Date.parse(request.sectionStartAt),sectionEnd=Date.parse(request.sectionEndAt);if(!Number.isNaN(sectionStart)&&!Number.isNaN(sectionEnd)&&!(sectionStart<=start&&start<end&&end<=sectionEnd))errors.push('Request.timer must satisfy sectionStartAt <= startAt < endAt <= sectionEndAt.');}}}if(has(request,'watermark')){const value=request.watermark;if(!isObject(value))errors.push('Request.watermark must be an object.');else{unknownFields(value,['contentBase64'],'Request.watermark',errors);if(requiredString(value,'contentBase64','Request.watermark',errors)&&(!/^[A-Za-z0-9+/]*={0,2}$/.test(value.contentBase64)||value.contentBase64.length%4===1))errors.push('Request.watermark.contentBase64 must be base64 text.');if(typeof value.contentBase64==='string'&&value.contentBase64.length>2796204)errors.push('Request.watermark.contentBase64 exceeds the 2 MiB PNG limit.');}}if(has(request,'sourceVideo')){const value=request.sourceVideo;if(!isObject(value))errors.push('Request.sourceVideo must be an object.');else{unknownFields(value,['fileId','name','mimeType'],'Request.sourceVideo',errors);requiredString(value,'fileId','Request.sourceVideo',errors);['name','mimeType'].forEach(key=>{if(has(value,key)&&typeof value[key]!=='string')errors.push('Request.sourceVideo.'+key+' must be a string.');});}}[['outputFormat',['h264-mp4','prores-422-mov']],['fitMode',['letterbox','pillarbox','crop']],['audioMode',['source+heartbeat','source-only','heartbeat-only']]].forEach(([key,allowed])=>{if(has(request,key)){if(typeof request[key]!=='string')errors.push('Request.'+key+' must be a string.');else if(!allowed.includes(request[key]))errors.push('Request.'+key+' has an unsupported value.');if(!has(request,'sourceVideo'))errors.push('Request.'+key+' requires sourceVideo.fileId.');}});return errors;}"
     "const validateRequestStructure=validateRequest;validateRequest=function(request){const errors=validateRequestStructure(request);if(isObject(request)&&has(request,'displayTimeZone')&&typeof request.displayTimeZone==='string'&&request.displayTimeZone&&!validTimeZone(request.displayTimeZone))errors.push('Request.displayTimeZone must be a valid IANA timezone identifier.');return errors;};"
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
     "function applyRequest(request){if(!request||typeof request!=='object'||Array.isArray(request))throw new Error('JSON must be an object');request={...request,futureTraceOpacityPercent:has(request,'futureTraceOpacityPercent')?request.futureTraceOpacityPercent:25};['telemetryFormat','preset','futureTraceOpacityPercent','outputFormat','fitMode','audioMode'].forEach(key=>{if(request[key]!==undefined&&byId(key.replace(/[A-Z]/g,letter=>'-'+letter.toLowerCase())))byId(key.replace(/[A-Z]/g,letter=>'-'+letter.toLowerCase())).value=request[key];});byId('source-video-file-id').value=request.sourceVideo?.fileId||'';setDisplayTimeZone(request.displayTimeZone);[['telemetrySyncAt','telemetry-sync-at'],['cameraSyncAt','camera-sync-at'],['sectionStartAt','section-start-at'],['sectionEndAt','section-end-at']].forEach(([key,id])=>{byId(id).value=request[key]?isoToLocal(request[key]):'';});clearFileBackedValue('telemetry');byId('telemetry').value=request.telemetry||'';byId('telemetry-file').value='';show(byId('telemetry-status'),request.telemetry?'Loaded from JSON.':'');const hasSpo2=!!request.spo2;byId('spo2-enabled').checked=hasSpo2;refreshOptional('spo2-enabled','spo2-fields');clearFileBackedValue('spo2-telemetry');byId('spo2-telemetry').value=hasSpo2?(request.spo2.telemetry||''):'';show(byId('spo2-status'),hasSpo2?'Loaded from JSON.':'');const hasTimer=!!request.timer;byId('timer-enabled').checked=hasTimer;refreshOptional('timer-enabled','timer-fields');byId('timer-start-at').value=hasTimer?isoToLocal(request.timer.startAt):'';byId('timer-end-at').value=hasTimer?isoToLocal(request.timer.endAt):'';const hasWatermark=!!request.watermark;byId('watermark-enabled').checked=hasWatermark;refreshOptional('watermark-enabled','watermark-fields');byId('watermark-file').value='';clearFileBackedValue('watermark-content');byId('watermark-content').value=hasWatermark?(request.watermark.contentBase64||''):'';show(byId('watermark-status'),hasWatermark?'Loaded from JSON.':'');updateTelemetryAccept();writeRequest(request);invalidatePreview();show(jsonStatus,'JSON applied to the form.','success');show(status,'Ready to preview or submit.','success');}"
     "byId('apply-json').addEventListener('click',()=>{try{const request=JSON.parse(raw.value),errors=validateRequest(request);if(errors.length){show(jsonStatus,errors.map((error,index)=>(index+1)+'. '+error).join('\\n'),'error');return;}applyRequest(request);}catch(error){show(jsonStatus,error.message,'error');}});byId('copy-json').addEventListener('click',()=>{const text=syncRequest(false)||raw.value;navigator.clipboard?navigator.clipboard.writeText(text).then(()=>show(jsonStatus,'Generated JSON copied.','success')):show(jsonStatus,'Copy is unavailable in this browser.');});"
     "const previewButton=byId('preview-button'),previewSpinner=previewButton.querySelector('.button-spinner');function newPreviewGeneration(){return crypto.randomUUID?crypto.randomUUID():String(Date.now())+'-'+Math.random();}function newSubmitIdempotencyKey(){return 'ui-submit-'+(crypto.randomUUID?crypto.randomUUID():String(Date.now())+'-'+Math.random());}let previewGeneration=newPreviewGeneration(),previewActive=false,submitState='idle',submittedJobId=null,submitIdempotencyKey=newSubmitIdempotencyKey();function setSubmitDisabled(){submitButton.disabled=submitState!=='idle';submitButton.setAttribute('aria-disabled',String(submitButton.disabled));}function setPreviewActive(active){previewActive=active;previewButton.disabled=active;previewButton.setAttribute('aria-disabled',String(active));previewSpinner.hidden=!active;}function setPreviewStatus(state){setSubmitDisabled();if(submitState!=='idle')return;const messages={required:'Preview is optional.',pending:'Preview is running. Submit remains available.',succeeded:'Preview succeeded.',failed:'Preview failed. Submit remains available.',stale:'Preview no longer matches current settings. Submit remains available.',expired:'Preview expired. Submit remains available.'};show(submitStatus,messages[state]||messages.required,state==='succeeded'?'success':(['failed','stale','expired'].includes(state)?'error':''));}function localPreviewState(className,title,message){const result=byId('preview-result');result.className=className;result.setAttribute('role',className==='preview-pending'?'status':'alert');result.dataset.previewGeneration=previewGeneration;delete result.dataset.previewOperation;result.replaceChildren();const heading=document.createElement('h2'),copy=document.createElement('p');heading.textContent=title;copy.textContent=message;result.append(heading,copy);return result;}function beginPreview(){previewGeneration=newPreviewGeneration();setPreviewStatus('pending');setPreviewActive(true);localPreviewState('preview-pending','Preparing preview','Waiting for the preview service.');show(status,'Preparing preview…');}function finishPreview(){setPreviewActive(false);}function invalidatePreview(){const hadPreview=previewActive||!!byId('preview-result')?.dataset?.previewOperation;previewGeneration=newPreviewGeneration();submitState='idle';submittedJobId=null;submitIdempotencyKey=newSubmitIdempotencyKey();setPreviewStatus(hadPreview?'stale':'required');finishPreview();if(hadPreview){localPreviewState('preview-stale','Preview settings changed','Start a new preview for the current settings.');show(status,'Preview settings changed.','');}}"
     "function previewTrigger(event){const trigger=event.detail?.elt||event.target;return !!trigger?.matches?.('[hx-post=\"/ui/preview\"]');}function previewTarget(event){const target=event.detail?.elt?.id==='preview-result'?event.detail.elt:event.target?.id==='preview-result'?event.target:event.detail?.target;return target?.id==='preview-result'?target:null;}function previewRequestEvent(event){const trigger=event.detail?.elt||event.target;return previewTrigger(event)||trigger?.id==='preview-result'||!!trigger?.closest?.('#preview-result')||!!previewTarget(event);}function requestPreviewGeneration(event){const trigger=event.detail?.elt||event.target;return event.detail?.requestConfig?.headers?.['X-Preview-Generation']||trigger?.dataset?.previewGeneration;}function previewEventGeneration(event){return event.detail?.xhr?.aggPreviewGeneration||event.detail?.requestConfig?.headers?.['X-Preview-Generation']||event.detail?.xhr?.getResponseHeader?.('X-Preview-Generation')||requestPreviewGeneration(event)||previewTarget(event)?.dataset?.previewGeneration||previewGeneration;}function currentPreviewEvent(event){return previewEventGeneration(event)===previewGeneration;}function transportFailure(event,kind){if(!previewRequestEvent(event)||!previewActive||!currentPreviewEvent(event))return;finishPreview();setPreviewStatus('failed');const admission=' No durable render was submitted. If a Preview started, its reservation remains counted.';if(kind==='platform'){localPreviewState('preview-error','Preview did not finish','The preview service did not finish this preview.'+admission+' Retry with the Preview button.');show(status,'Preview failed. See details below.','error');}else if(kind==='connection'){localPreviewState('preview-error','Preview connection lost','The preview connection was lost.'+admission+' Check the connection, then retry.');show(status,'Preview connection lost.','error');}else if(kind==='cancelled'){localPreviewState('preview-error','Preview request cancelled','The browser cancelled this preview request.'+admission+' Retry when ready.');show(status,'Preview request cancelled.','error');}else{localPreviewState('preview-error','Preview did not finish','The browser stopped waiting for the preview service.'+admission+' Retry with the Preview button.');show(status,'Preview timed out.','error');}}function submitRequestEvent(event){const trigger=event.detail?.elt||event.target;return submitState==='submitting'&&!previewRequestEvent(event)&&(trigger===form||trigger===submitButton||trigger?.closest?.('#render-form')===form);}function submitTransportFailure(event,kind){if(!submitRequestEvent(event))return;submitState='idle';setSubmitDisabled();const presentations={platform:['Submission failed. Review the error below, then retry Submit render.','Durable render was not submitted. Retry when ready.'],connection:['Submission connection lost. Retry Submit render. Repeating is safe.','Submission connection lost. Retry when ready.'],timeout:['Submission timed out. Retry Submit render. Repeating is safe.','Submission timed out. Retry when ready.'],cancelled:['Submission cancelled. Retry Submit render. Repeating is safe.','Submission cancelled. Retry when ready.']},presentation=presentations[kind]||presentations.connection;show(submitStatus,presentation[0],'error');show(status,presentation[1],'error');}function requestFailure(event,kind){transportFailure(event,kind);submitTransportFailure(event,kind);}"
     "function jobSwap(event){const submissionResponse=(event.detail?.target||event.target)?.id==='job-result';for(const node of [event.detail?.target,event.detail?.elt,event.target]){if(!node?.matches)continue;const result=node.id==='job-result'?node:node.closest?.('#job-result');if(!result)continue;const job=node.matches('.job[data-job-state]')?node:result.querySelector('.job[data-job-state]');if(job)return {id:job.id,state:job.dataset.jobState,submissionResponse};}return null;}function acceptSubmission(job){if(submitState==='submitting'){if(!job.submissionResponse)return;submittedJobId=job.id;}else if(submitState!=='submitted'||job.id!==submittedJobId)return;submitState='submitted';setSubmitDisabled();const presentations={succeeded:['Render succeeded. Change any render setting to start another render.','Durable render succeeded. Open the result below.','success'],failed:['Render failed. Review the result below, then change any render setting to start another render.','Durable render failed. Review the result below.','error'],cancelled:['Render cancelled. Change any render setting to start another render.','Durable render cancelled.','error'],'cancellation-requested':['Cancellation requested. Change any render setting to start another render.','Durable render cancellation requested.','']},presentation=presentations[job.state]||['Submitted. Change any render setting to start another render.','Durable render submitted. Track its progress below.','success'];show(submitStatus,presentation[0],presentation[2]);show(status,presentation[1],presentation[2]);}"
     "form.addEventListener('input',event=>{if(event.target.type==='file')return;if(event.target.id==='raw-json'){invalidatePreview();return;}clearFileBackedValue(event.target.id);invalidatePreview();syncRequest(false);});document.body.addEventListener('htmx:configRequest',event=>{if(!form.contains(event.target))return;const preview=previewTrigger(event);if(preview&&previewActive){event.preventDefault();return;}if(!preview&&submitState!=='idle'){event.preventDefault();return;}const text=syncRequest(true);if(!text){event.preventDefault();return;}event.detail.parameters.request=text;if(preview){beginPreview();event.detail.headers['X-Preview-Generation']=previewGeneration;}else{submitState='submitting';setSubmitDisabled();event.detail.headers['Idempotency-Key']=submitIdempotencyKey;show(submitStatus,'Submitting durable render…');show(status,'Submitting durable render…');}});document.body.addEventListener('htmx:beforeSend',event=>{if(previewRequestEvent(event)&&event.detail?.xhr)event.detail.xhr.aggPreviewGeneration=requestPreviewGeneration(event)||previewGeneration;});document.body.addEventListener('htmx:beforeSwap',event=>{const target=previewTarget(event);if(!target)return;const generation=previewEventGeneration(event);if(generation&&generation!==previewGeneration)event.detail.shouldSwap=false;});document.body.addEventListener('htmx:afterSwap',event=>{const job=jobSwap(event);if(job)acceptSubmission(job);});document.body.addEventListener('htmx:afterSettle',event=>{const target=previewTarget(event);if(!target)return;if(!currentPreviewEvent(event))return;if(target.matches('.preview-pending')){setPreviewStatus('pending');setPreviewActive(true);show(status,'Preparing preview…');}else{finishPreview();if(target.matches('.preview-error')){setPreviewStatus('failed');show(status,'Preview failed. See details below.','error');}else if(target.matches('.preview-gallery,.preview-empty')){setPreviewStatus('succeeded');show(status,target.matches('.preview-gallery')?'Preview ready.':'Preview completed with no moments.','success');}else if(target.matches('.preview-stale')){setPreviewStatus('expired');show(status,'Preview expired.','error');}}});document.body.addEventListener('htmx:responseError',event=>requestFailure(event,'platform'));document.body.addEventListener('htmx:sendError',event=>requestFailure(event,'connection'));document.body.addEventListener('htmx:timeout',event=>requestFailure(event,'timeout'));document.body.addEventListener('htmx:sendAbort',event=>requestFailure(event,'cancelled'));document.body.addEventListener('htmx:xhr:abort',event=>requestFailure(event,'cancelled'));"
     "let previewOpener=null,previewIndex=0;function previewImages(){return [...(byId('preview-result')?.querySelectorAll('.preview-open')||[])];}function showPreviewImage(index){const dialog=byId('preview-dialog'),images=previewImages(),open=images[index],image=dialog?.querySelector('img'),title=byId('preview-dialog-title'),counter=byId('preview-dialog-counter'),previous=dialog?.querySelector('.preview-previous'),next=dialog?.querySelector('.preview-next');if(!dialog||!open||!image||!title||!counter||!previous||!next)return false;previewIndex=index;image.src=open.dataset.full;image.alt=open.dataset.alt;title.textContent=open.dataset.title;counter.textContent='Image '+(index+1)+' of '+images.length;previous.disabled=index===0;next.disabled=index===images.length-1;return true;}function movePreviewImage(delta){showPreviewImage(previewIndex+delta);}document.body.addEventListener('click',event=>{const open=event.target.closest?.('.preview-open');if(open){const dialog=byId('preview-dialog'),index=previewImages().indexOf(open);if(!dialog||index<0||!showPreviewImage(index))return;previewOpener=open;dialog.showModal();dialog.focus();return;}if(event.target.closest?.('.preview-dialog-close'))byId('preview-dialog')?.close();else if(event.target.closest?.('.preview-previous'))movePreviewImage(-1);else if(event.target.closest?.('.preview-next'))movePreviewImage(1);});document.body.addEventListener('keydown',event=>{const dialog=byId('preview-dialog');if(!dialog?.open||event.target!==dialog)return;if(event.key==='ArrowLeft'){event.preventDefault();movePreviewImage(-1);}else if(event.key==='ArrowRight'){event.preventDefault();movePreviewImage(1);}});document.body.addEventListener('close',event=>{if(event.target.id==='preview-dialog'&&previewOpener){previewOpener.focus();previewOpener=null;}},true);"
     (picker-script picker-config)
     "})();</script></div></body></html>")))

(defn- public-page [title body]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       (icon-links)
       "<title>" title " · Alpha Compose</title>"
       "<meta name=\"color-scheme\" content=\"dark\">"
       "<style>"
       ":root{font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;line-height:1.45}"
       "*{box-sizing:border-box}body{margin:0}.shell{max-width:78rem;margin:0 auto;padding:2rem 1.25rem 4rem}"
       ".public-header{display:flex;justify-content:space-between;gap:1rem;align-items:center;margin:1rem 0 2rem}.brand{color:var(--color-text);font-weight:800;letter-spacing:-.03em;text-decoration:none}"
       "nav{display:flex;gap:1rem;flex-wrap:wrap}nav a,footer a{color:var(--color-link)}"
       "h1,h2,p{margin-top:0}h1{font-size:clamp(2.5rem,6vw,5rem);line-height:1.02;letter-spacing:-.06em;max-width:11ch;margin-bottom:1rem}h2{font-size:1.25rem;margin-bottom:.35rem}"
       ".muted{color:var(--color-muted)}.eyebrow{color:var(--color-accent);font-size:.75rem;font-weight:800;letter-spacing:.12em;text-transform:uppercase}"
       ".hero{display:grid;grid-template-columns:minmax(0,1.2fr) minmax(18rem,.8fr);gap:1rem;align-items:stretch;margin:2.5rem 0 1rem}.hero-copy{padding:2rem 0 1rem}"
       ".hero-card,.card{background:var(--color-surface);border:1px solid var(--color-border);border-radius:1.1rem;box-shadow:var(--shadow-surface);padding:1.35rem}.hero-card{background:var(--color-surface-strong);color:var(--color-text);border-color:var(--color-border-strong);display:flex;flex-direction:column;justify-content:space-between}.hero-card .muted{color:#c8ddef}"
       ".step{color:var(--color-subtle);font-weight:800;font-size:.8rem}.hero-card .step{color:var(--color-accent)}.hero-card-note{color:#c8ddef;margin:2rem 0 0}"
       ".actions{display:flex;align-items:center;gap:.75rem;flex-wrap:wrap;margin-top:1.25rem}.button,.cta{border:1px solid var(--color-border);border-radius:.65rem;padding:.7rem 1rem;font-weight:800;cursor:pointer;background:var(--color-surface-soft);color:var(--color-text);text-decoration:none;display:inline-block}.button.primary,.cta{background:var(--color-accent);color:var(--color-accent-ink);box-shadow:0 .35rem .8rem #0fc3ff2e}"
       "form.card{display:grid;gap:.55rem;margin-top:1rem}input,textarea{width:100%;min-width:0;border:1px solid #6b8ba5;border-radius:.55rem;padding:.7rem;font:inherit;color:var(--color-text);background:#06182b}input[readonly]{background:#10263c;color:#c1d3e4}textarea{resize:vertical}.card:focus{outline:3px solid var(--color-warning);outline-offset:3px}"
       ".feature-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:1rem;margin:1rem 0}.feature-grid .card{margin:0}.trust-card{margin-top:1rem}.trust-card p:last-child{margin-bottom:0}"
       "footer{margin-top:2rem;color:var(--color-muted)}footer a{margin-right:.75rem}"
       "@media(max-width:680px){.shell{padding:1rem .8rem 3rem}.public-header{display:block}.public-header nav{margin-top:1rem}.hero{grid-template-columns:1fr;margin-top:1.5rem}.hero-copy{padding:1rem 0}.feature-grid{grid-template-columns:1fr}}"
       (theme-style)
       ".public-header{padding:.75rem 1rem;border:1px solid var(--color-border);border-radius:1rem;background:#06182be8;box-shadow:var(--shadow-surface)}"
       ".brand{color:var(--color-text)}.hero-copy{padding:clamp(1.25rem,3vw,2rem);background:var(--color-surface);border:1px solid var(--color-border);border-radius:1.1rem;box-shadow:var(--shadow-surface)}"
       "</style></head><body data-theme=\"telemetry\"><div class=\"shell\">"
       "<header class=\"public-header\"><a class=\"brand\" href=\"/\">Alpha Compose</a><nav><a href=\"/privacy\">Privacy</a>"
       "<a href=\"/terms\">Terms</a></nav></header><main>" body "</main>"
       "<footer><small>© 2026 Alpha Compose · <a href=\"mailto:me@jamiep.org\">Contact</a></small></footer>"
       "</div></body></html>"))

(def anonymous-page
  (public-page
   "Finished activity videos"
   (str "<section class=\"hero\"><div class=\"hero-copy\">"
        "<div class=\"eyebrow\">Workout data, built into your video</div>"
        "<h1>Turn your activity into a video worth sharing.</h1>"
        "<p class=\"muted\">Choose a video and your activity data. Alpha Compose adds "
        "heart rate and other supported stats, lets you preview the result, and "
        "delivers a finished video to Google Drive. No video editing required.</p>"
        "<div class=\"actions\"><a class=\"cta\" href=\"/v1/auth/login/start\">Continue with Google</a></div>"
        "<p class=\"muted\">Continue with Google verifies your account and lets you "
        "choose the files Alpha Compose needs.</p>"
        "</div><aside class=\"hero-card\"><div><div class=\"step\">How it works</div>"
        "<h2>Your video, finished for you.</h2>"
        "<p class=\"muted\">Select a video, add your activity data, and preview how it looks. "
        "Alpha Compose combines everything and saves the finished video to Google Drive.</p>"
        "</div><p class=\"hero-card-note\">A ready-to-share video, without a separate editing step.</p>"
        "</aside></section>"
        "<section class=\"feature-grid\"><article class=\"card\"><div class=\"step\">01</div>"
        "<h2>Choose your video and data</h2><p class=\"muted\">Pick the video and supported activity data you want to use.</p>"
        "</article><article class=\"card\"><div class=\"step\">02</div>"
        "<h2>Make it yours</h2><p class=\"muted\">Line up the timing, choose how the stats look, and preview key moments.</p>"
        "</article><article class=\"card\"><div class=\"step\">03</div>"
        "<h2>Get the finished video</h2><p class=\"muted\">Alpha Compose combines everything and saves a ready-to-share video to Google Drive.</p>"
        "</article></section>"
        "<section class=\"card trust-card\"><div class=\"step\">Access &amp; privacy</div>"
        "<h2>Your Google Drive stays under your control.</h2>"
        "<p class=\"muted\">Alpha Compose can only use files you choose and the finished "
        "videos it creates. It cannot access the rest of your Google Drive.</p></section>")))

(def drive-recovery-fragment
  (str "<section class=\"notice\" role=\"alert\"><h2>Google Drive access needs renewal</h2>"
       "<p>Your session was cleared because Alpha Compose could no longer use its restricted "
       "<code>drive.file</code> grant. No render was submitted.</p>"
       "<p><a class=\"button primary\" href=\"/v1/auth/login/start?recovery=true\">"
       "Continue with Google</a></p></section>"))

(def drive-recovery-page
  (public-page
   "Google Drive access needs renewal"
   (str "<section class=\"hero\"><div class=\"hero-copy\"><div class=\"eyebrow\">Authorization required</div>"
        "<h1>Reconnect Google to continue.</h1>"
        "<p class=\"muted\">Alpha Compose cleared your browser session because its restricted "
        "<code>drive.file</code> grant is missing, expired, or revoked. Continue explicitly to "
        "restore identity, file selection, and Drive delivery access. No render was submitted.</p>"
        "<div class=\"actions\"><a class=\"cta\" href=\"/v1/auth/login/start?recovery=true\">"
        "Continue with Google</a></div></div></section>")))

(defn early-access-page
  [{:keys [email proof instagram message feedback request-id]}]
  (public-page
   "Early access"
   (str
    "<section class=\"hero\"><div class=\"hero-copy\">"
    "<div class=\"eyebrow\">Verified Google account</div>"
    "<h1>Alpha Compose is in early access</h1>"
    "<p class=\"muted\">Access is currently limited to approved testers. "
    "If you would like to test Alpha Compose, leave your details below.</p>"
    "<p><strong>No session, Drive grant, membership binding, or render was created.</strong></p>"
    (when feedback
      (str "<div class=\"card\" role=\"" (if (= :success (:kind feedback))
                                           "status" "alert")
           "\" tabindex=\"-1\" id=\"early-access-feedback\"><h2>"
           (escape-html (:title feedback)) "</h2><p>"
           (escape-html (:message feedback)) "</p></div>"))
    (when (and email proof)
      (str
       "<form class=\"card\" method=\"post\" action=\"/v1/early-access/request\" "
       "aria-describedby=\"early-access-privacy form-status\">"
       "<input type=\"hidden\" name=\"proof\" value=\"" (escape-html proof) "\">"
       "<label for=\"early-access-email\"><strong>Email</strong></label>"
       "<input id=\"early-access-email\" type=\"email\" name=\"email\" value=\""
       (escape-html email) "\" readonly required>"
       "<p class=\"muted\">This is the Google email address just verified.</p>"
       "<label for=\"early-access-instagram\"><strong>Instagram handle (optional)</strong></label>"
       "<input id=\"early-access-instagram\" name=\"instagram\" maxlength=\"64\" value=\""
       (escape-html instagram) "\">"
       "<label for=\"early-access-message\"><strong>Message (optional)</strong></label>"
       "<textarea id=\"early-access-message\" name=\"message\" maxlength=\"2000\" rows=\"6\">"
       (escape-html message) "</textarea>"
       "<p id=\"early-access-privacy\" class=\"muted\">Your details are used only to email "
       "the Alpha Compose operator about this request. Alpha Compose does not retain them.</p>"
       "<p id=\"form-status\" aria-live=\"polite\"></p>"
       "<button class=\"button primary\" type=\"submit\">Ask to test Alpha Compose</button>"
       "</form>"))
    "<div class=\"actions\"><a href=\"mailto:me@jamiep.org\">Email me@jamiep.org directly</a>"
    "<a href=\"/v1/auth/login/start\">Try another Google account</a></div>"
    (when request-id
      (str "<p class=\"muted\"><small>Request ID: "
           (escape-html request-id) "</small></p>"))
    "</div></section>"
    (when feedback
      "<script>document.getElementById('early-access-feedback')?.focus();</script>"))))

(def privacy-page
  (public-page
   "Privacy policy"
   (str "<h1>Privacy policy</h1><p><strong>Effective 22 July 2026.</strong></p>"
        "<p>Questions or deletion "
        "requests may be sent to <a href=\"mailto:me@jamiep.org\">me@jamiep.org</a>.</p>"
        "<h2>Information used</h2><p>We use your Google account identifier and "
        "email address to authenticate you and enforce the administrator-managed access list. "
        "As part of the same authorization, Alpha Compose receives only the "
        "<code>drive.file</code> permission, allowing access to files you select or "
        "that Alpha Compose creates. We process telemetry and optional watermark "
        "content solely to create the requested overlay. For an early-access request, "
        "we collect the verified Google email address, an optional Instagram handle, "
        "and an optional message solely so the operator can respond about testing.</p>"
        "<h2>Storage and retention</h2><p>Encrypted Google Drive authorization, "
        "membership, and job records are stored in Google Cloud. Temporary request "
        "and output objects are deleted after 24 hours; job metadata is scheduled for "
        "deletion after 90 days. Completed overlays are delivered to your Google Drive "
        "and remain there until you delete them. Alpha Compose does not retain early-access requests "
        "in Firestore, application logs, analytics, or another application data store. "
        "Those details exist only during bounded request processing and in the configured "
        "email processor and recipient mailbox.</p>"
        "<h2>Sharing and security</h2><p>We use Google Cloud and Google Drive to "
        "operate the service, and Resend processes the plain-text early-access notification. "
        "We do not sell personal information or use telemetry for "
        "advertising. Access is limited to approved accounts; credentials are encrypted, "
        "and application logs exclude telemetry values, filenames, email addresses, and tokens. "
        "Use and transfer of information received from Google APIs follows the "
        "<a href=\"https://developers.google.com/terms/api-services-user-data-policy\">Google API Services User Data Policy</a>, "
        "including its Limited Use requirements.</p>"
        "<h2>Your choices</h2><p>You may disconnect Alpha Compose in your Google Account, "
        "delete delivered files from Drive, or email me@jamiep.org with a contact or deletion request "
        "covering service records or an early-access notification. "
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
