(ns agg.development-gate-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private expected-sha
  "0123456789abcdef0123456789abcdef01234567")

(def ^:private repository "jamiepratt/animated-graph-cloud")

(defn- development-run [overrides]
  (merge {:id 10701
          :run_number 91
          :head_sha expected-sha
          :head_branch "main"
          :event "push"
          :path ".github/workflows/deploy.yml"
          :status "completed"
          :conclusion "success"
          :repository {:full_name repository}
          :head_repository {:full_name repository}}
         overrides))

(defn- gate-result [sha runs]
  (let [process (-> (ProcessBuilder.
                     ["bash" "script/require_development_success.sh"
                      sha repository])
                    (.redirectErrorStream true)
                    (.start))]
    (with-open [writer (io/writer (.getOutputStream process))]
      (json/write {:workflow_runs runs} writer))
    {:exit (.waitFor process)
     :output (slurp (.getInputStream process))}))

(deftest exact-trusted-development-success-opens-the-production-gate
  (let [{:keys [exit output]}
        (gate-result expected-sha [(development-run {})])]
    (is (= 0 exit) output)
    (is (re-find #"same-commit development succeeded" output))))

(deftest development-gate-fails-closed
  (doseq [[description expected-exit runs]
          [["missing result" 75 []]
           ["wrong SHA" 75
            [(development-run
              {:head_sha "fedcba9876543210fedcba9876543210fedcba98"})]]
           ["failed result" 1
            [(development-run {:conclusion "failure"})]]
           ["cancelled result" 1
            [(development-run {:conclusion "cancelled"})]]
           ["timed-out result" 1
            [(development-run {:conclusion "timed_out"})]]
           ["unfinished result" 75
            [(development-run {:status "in_progress" :conclusion nil})]]
           ["ambiguous latest result" 1
            [(development-run {})
             (development-run {:id 10702})]]
           ["wrong workflow path" 75
            [(development-run {:path ".github/workflows/other.yml"})]]
           ["wrong branch" 75
            [(development-run {:head_branch "feature"})]]
           ["wrong event" 75
            [(development-run {:event "pull_request"})]]
           ["wrong repository" 75
            [(development-run
              {:repository {:full_name "untrusted/fork"}})]]
           ["fork head repository" 75
            [(development-run
              {:head_repository {:full_name "untrusted/fork"}})]]]]
    (testing description
      (let [{:keys [exit output]} (gate-result expected-sha runs)]
        (is (= expected-exit exit) output)))))

(deftest only-the-newest-trusted-result-controls-the-gate
  (let [{:keys [exit output]}
        (gate-result
         expected-sha
         [(development-run {:run_number 90})
          (development-run {:id 10702
                            :run_number 91
                            :conclusion "failure"})])]
    (is (= 1 exit) output)))

(deftest exact-manual-development-recovery-can-open-the-gate
  (let [{:keys [exit output]}
        (gate-result
         expected-sha
         [(development-run {:event "workflow_dispatch"})])]
    (is (= 0 exit) output)))

(deftest untrusted-workflow-metadata-is-neither-executed-nor-logged
  (let [metadata "$(printf untrusted-workflow-metadata)"
        {:keys [exit output]}
        (gate-result
         expected-sha
         [(development-run {:display_title metadata})])
        script (slurp "script/require_development_success.sh")]
    (is (= 0 exit) output)
    (is (not (str/includes? output metadata)))
    (is (not (re-find #"\b(eval|source)\b" script)))))
