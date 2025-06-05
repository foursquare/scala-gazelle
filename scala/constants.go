package scala

const (
	LANGUAGE_NAME = "scala"

	JAVA_EXT  = ".java"
	SCALA_EXT = ".scala"

	SCALA_LIB_KIND   = "scala_library"
	SCALA_MACRO_KIND = "scala_macro_library"

	SCALA_JUNIT_TEST_KIND = "scala_junit_test"
	SCALA_TEST_KIND       = "scala_test"

	DEFAULT_RULES_SCALA_REPO_NAME = "rules_scala"
)

var (
	DEFAULT_SCALA_TEST_FILE_SUFFIXES = []string{
		"Test.scala",
	}

	DEFAULT_VISIBILITY = []string{
		"//:__subpackages__",
	}

	KNOWN_BUILD_FILENAMES = []string{
		"BUILD",
		"BUILD.bazel",
	}
)
