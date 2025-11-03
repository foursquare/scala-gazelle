package jvm

import "github.com/emirpasic/gods/sets/treeset"

const (
	LANGUAGE_NAME = "jvm"

	DEFAULT_MAVEN_INSTALL_FILE = "maven_install.json"
	DEFAULT_MAVEN_REPO_NAME    = "maven"
	DEFAULT_MAVEN_LABEL_PREFIX = "@" + DEFAULT_MAVEN_REPO_NAME + "//:"
)

var (
	DEFAULT_ARTIFACT_EXCLUDES = treeset.NewWithStringComparator(
		// Built-in Scala libraries which are on the classpath by default.
		"@maven//:org_scala_lang_scala_library",
	)

	DEFAULT_PACKAGE_MAP = map[string]*treeset.Set{
		// There is nothing here now, but packages may be added if they would otherwise need
		// to be handled manually by all users. Settings here are impossible for users to
		// override however, so this should be done with caution.
	}

	DEFAULT_FORCED_TRANSITIVE_DEPS = map[string][]string{}
)
