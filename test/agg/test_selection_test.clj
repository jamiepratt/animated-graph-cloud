(ns agg.test-selection-test
  (:require [agg.test-selection :as selection]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]))

(def ^:private dependency-sources
  {"src/agg/core.clj" "(ns agg.core)"
   "src/agg/service.clj" "(ns agg.service (:require [agg.core]))"
   "test/agg/core_test.clj"
   "(ns agg.core-test (:require [agg.core] [clojure.test]))"
   "test/agg/service_test.clj"
   "(ns agg.service-test (:require [agg.service] [clojure.test]))"})

(deftest changed-production-namespaces-select-direct-and-transitive-tests
  (let [result (selection/select-affected
                {:changes [{:status :modified :path "src/agg/core.clj"}]
                 :sources dependency-sources
                 :test-namespaces ['agg.core-test 'agg.service-test]})]
    (is (= #{'agg.core-test 'agg.service-test}
           (set (:tests result))))
    (is (= #{"direct dependency on agg.core"}
           (get-in result [:reasons 'agg.core-test])))
    (is (= #{"transitive dependency on agg.core"}
           (get-in result [:reasons 'agg.service-test])))))

(deftest changed-tests-deletions-and-renames-remain-explainable
  (testing "a changed test selects itself"
    (let [result (selection/select-affected
                  {:changes [{:status :modified
                              :path "test/agg/core_test.clj"}]
                   :sources dependency-sources
                   :test-namespaces ['agg.core-test 'agg.service-test]})]
      (is (= ['agg.core-test] (:tests result)))
      (is (= #{"changed test namespace"}
             (get-in result [:reasons 'agg.core-test])))))
  (testing "deleted source uses historical content"
    (let [sources (dissoc dependency-sources "src/agg/core.clj")
          result (selection/select-affected
                  {:changes [{:status :deleted
                              :path "src/agg/core.clj"
                              :source "(ns agg.core)"}]
                   :sources sources
                   :test-namespaces ['agg.core-test 'agg.service-test]})]
      (is (= #{'agg.core-test 'agg.service-test} (set (:tests result))))))
  (testing "rename considers both old and new namespaces"
    (let [sources (-> dependency-sources
                      (dissoc "src/agg/core.clj")
                      (assoc "src/agg/foundation.clj" "(ns agg.foundation)"))
          result (selection/select-affected
                  {:changes [{:status :renamed
                              :old-path "src/agg/core.clj"
                              :old-source "(ns agg.core)"
                              :path "src/agg/foundation.clj"}]
                   :sources sources
                   :test-namespaces ['agg.core-test 'agg.service-test]})]
      (is (= #{'agg.core-test 'agg.service-test} (set (:tests result)))))))

(deftest non-clojure-impact-rules-and-unknown-paths-are-conservative
  (let [tests ['agg.contracts-test 'agg.deploy-workflow-test
               'agg.proto-release-test 'agg.release-config-test
               'agg.service-test]]
    (testing "OpenAPI has an explicit bounded impact rule"
      (let [result (selection/select-affected
                    {:changes [{:status :modified :path "docs/openapi.yaml"}]
                     :sources dependency-sources
                     :test-namespaces tests})]
        (is (= #{'agg.contracts-test 'agg.release-config-test}
               (set (:tests result))))
        (is (every? #(re-find #"docs/openapi.yaml" %)
                    (mapcat val (:reasons result))))))
    (testing "proto Terraform selects its release contracts"
      (let [result (selection/select-affected
                    {:changes [{:status :modified
                                :path "infra/proto/main.tf"}]
                     :sources dependency-sources
                     :test-namespaces tests})]
        (is (= #{'agg.deploy-workflow-test
                 'agg.proto-release-test
                 'agg.release-config-test}
               (set (:tests result))))))
    (testing "proto identity files select proto release contracts"
      (doseq [path ["docs/proto/CHANGELOG.md"
                    "resources/agg/proto-version.edn"]]
        (let [result (selection/select-affected
                      {:changes [{:status :modified :path path}]
                       :sources dependency-sources
                       :test-namespaces tests})]
          (is (= #{'agg.proto-release-test 'agg.release-config-test}
                 (set (:tests result)))))))
    (testing "shared configuration falls back to the complete suite"
      (let [result (selection/select-affected
                    {:changes [{:status :modified :path "deps.edn"}]
                     :sources dependency-sources
                     :test-namespaces tests})]
        (is (= tests (:tests result)))
        (is (:fallback? result))))
    (testing "unknown production paths fall back to the complete suite"
      (let [result (selection/select-affected
                    {:changes [{:status :modified :path "src/native/new.c"}]
                     :sources dependency-sources
                     :test-namespaces tests})]
        (is (= tests (:tests result)))
        (is (:fallback? result))))))

(defn- git! [root & args]
  (let [{:keys [exit out err]} (apply shell/sh "git" "-C" root args)]
    (when-not (zero? exit)
      (throw (ex-info err {:args args :exit exit})))
    out))

(defn- write! [root path contents]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file contents)))

(defn- with-git-repository [f]
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "agg-test-selection"
               (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (git! (.getPath root) "init" "-q")
      (git! (.getPath root) "config" "user.email" "test@example.invalid")
      (git! (.getPath root) "config" "user.name" "Test")
      (write! root "src/agg/core.clj" "(ns agg.core)\n")
      (git! (.getPath root) "add" ".")
      (git! (.getPath root) "commit" "-qm" "base")
      (f (.getPath root))
      (finally
        (doseq [file (reverse (file-seq root))]
          (io/delete-file file true))))))

(deftest git-change-discovery-covers-staged-unstaged-and-commit-ranges
  (with-git-repository
    (fn [root]
      (write! root "src/agg/core.clj" "(ns agg.core)\n;; unstaged\n")
      (is (= [{:status :modified :path "src/agg/core.clj"}]
             (selection/git-changes {:root root})))
      (git! root "add" "src/agg/core.clj")
      (write! root "README.md" "untracked\n")
      (is (= #{{:status :modified :path "src/agg/core.clj"}
               {:status :added :path "README.md"}}
             (set (selection/git-changes {:root root}))))
      (git! root "add" ".")
      (git! root "commit" "-qm" "changed")
      (let [head (git! root "rev-parse" "HEAD")
            base (git! root "rev-parse" "HEAD^")]
        (is (= []
               (selection/git-changes
                {:root root
                 :base (.trim head)
                 :head (.trim head)})))
        (is (= #{{:status :modified :path "src/agg/core.clj"}
                 {:status :added :path "README.md"}}
               (set (selection/git-changes
                     {:root root
                      :base (.trim base)
                      :head (.trim head)}))))))))
