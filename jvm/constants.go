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
		// universe is technically a lazy val and so doesn't show up in the maven install
		// package mapping, but the common pattern is to import from it as if it were a
		// source package. We just hardcode it as a special case here.
		"scala.reflect.runtime.universe": treeset.NewWithStringComparator(
			"@maven//:org_scala_lang_scala_reflect",
		),
	}

	DEFAULT_FORCED_TRANSITIVE_DEPS = map[string][]string{}
)
