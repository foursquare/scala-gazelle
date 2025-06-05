package jvm

import (
	"flag"
	"fmt"
	"log"
	"path"
	"path/filepath"
	"strings"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/rule"
	"github.com/emirpasic/gods/sets/treeset"
)

const (
	// JavaExcludeArtifact tells the resolver to disregard a given maven artifact.
	// Can be repeated.
	//
	// Defaults to SCALA_STD_LIBS.
	JavaExcludeArtifact = "java_exclude_artifact"

	// JavaMavenInstallFile represents the directive that controls where the
	// maven_install.json file is located.
	//
	// Defaults to DEFAULT_MAVEN_INSTALL_FILE.
	JavaMavenInstallFile = "java_maven_install_file"

	// JavaMavenRepositoryName tells the code generator what the repository name that
	// contains all maven dependencies is.
	//
	// Defaults to DEFAULT_MAVEN_REPO_NAME.
	JavaMavenRepositoryName = "java_maven_repository_name"

	// ScalaForcedTransitiveDeps provides a way to force additional labels to be added
	// as deps when a particular label is added as a dep. It takes two arguments: the
	// initial label and a comma separated string of other transitive dependency labels.
	//
	// This can be particularly useful with Scala code where transitive dependencies may
	// be required on the compile classpath without being referenced directly in code
	// (see https://github.com/bazelbuild/rules_scala/blob/v6.6.0/docs/dependency-tracking.md
	// for details) or to simply work around jars with broken poms.
	//
	// Defaults to DEFAULT_FORCED_TRANSITIVE_DEPS.
	ScalaForcedTransitiveDeps = "scala_forced_transitive_deps"
)

type JvmConfig struct {
	excludedArtifacts    *treeset.Set
	MavenInstall         *MavenInstallData
	MavenLabelPrefix     string
	ForcedTransitiveDeps *map[string][]string
}

func NewJvmConfig() *JvmConfig {
	return &JvmConfig{
		excludedArtifacts:    DEFAULT_ARTIFACT_EXCLUDES,
		MavenInstall:         nil,
		MavenLabelPrefix:     DEFAULT_MAVEN_LABEL_PREFIX,
		ForcedTransitiveDeps: &DEFAULT_FORCED_TRANSITIVE_DEPS,
	}
}

// NewChild creates a new child JvmConfig. It inherits desired values from the
// current JvmConfig.
func (c *JvmConfig) NewChild() *JvmConfig {
	childMap := make(map[string][]string, len(*c.ForcedTransitiveDeps))
	for key, value := range *c.ForcedTransitiveDeps {
		childMap[key] = value
	}

	return &JvmConfig{
		excludedArtifacts:    c.excludedArtifacts,
		MavenInstall:         c.MavenInstall,
		MavenLabelPrefix:     c.MavenLabelPrefix,
		ForcedTransitiveDeps: &childMap,
	}
}

func (c *JvmConfig) addExcludedArtifacts(artifacts *treeset.Set) {
	c.excludedArtifacts = c.excludedArtifacts.Union(artifacts)
}

func (c *JvmConfig) setMavenInstall(repoRoot string, filename string) {
	absPath := filepath.Join(repoRoot, filename)
	c.MavenInstall = ParseMavenInstall(absPath, c.MavenLabelPrefix, c.excludedArtifacts)
}

// JvmConfigs is an extension of map[string]*JvmConfig. It provides finding methods
// on top of the mapping.
type JvmConfigs map[string]*JvmConfig

// ParentForPackage returns the parent JvmConfig for the given Bazel package.
func (c *JvmConfigs) ParentForPackage(pkg string) *JvmConfig {
	dir := path.Dir(pkg)
	if dir == "." {
		dir = ""
	}

	parent := (map[string]*JvmConfig)(*c)[dir]
	return parent
}

func JvmConfigForConfig(c *config.Config, pkg string) *JvmConfig {
	jvmConfigs := c.Exts[LANGUAGE_NAME].(*JvmConfigs)
	return (*jvmConfigs)[pkg]
}

// JvmConfigurer satisfies the config.Configurer interface. It's the
// language-specific configuration extension.
//
// See config.Configurer for more information.
type JvmConfigurer struct {
}

func NewJvmConfigurer() *JvmConfigurer {
	return &JvmConfigurer{}
}

func (jc *JvmConfigurer) getOrInitJvmConfigs(c *config.Config) *JvmConfigs {
	if _, exists := c.Exts[LANGUAGE_NAME]; !exists {
		jvmConfigs := JvmConfigs{
			"": NewJvmConfig(),
		}
		c.Exts[LANGUAGE_NAME] = &jvmConfigs
	}

	return c.Exts[LANGUAGE_NAME].(*JvmConfigs)
}

func (jc *JvmConfigurer) RegisterFlags(fs *flag.FlagSet, cmd string, c *config.Config) {
}

func (jc *JvmConfigurer) CheckFlags(fs *flag.FlagSet, c *config.Config) error {
	return nil
}

func (jc *JvmConfigurer) KnownDirectives() []string {
	return []string{
		JavaExcludeArtifact,
		JavaMavenInstallFile,
		JavaMavenRepositoryName,
		ScalaForcedTransitiveDeps,
	}
}

func (jc *JvmConfigurer) Configure(c *config.Config, rel string, f *rule.File) {
	jvmConfigs := jc.getOrInitJvmConfigs(c)

	jvmConfig, exists := (*jvmConfigs)[rel]
	if !exists {
		parent := jvmConfigs.ParentForPackage(rel)
		jvmConfig = parent.NewChild()
		(*jvmConfigs)[rel] = jvmConfig
	}

	if f != nil {
		var artifactExcludes *treeset.Set
		mavenInstallFile := ""

		for _, d := range f.Directives {
			switch d.Key {
			case JavaExcludeArtifact:
				if artifactExcludes == nil {
					artifactExcludes = treeset.NewWithStringComparator(d.Value)
				} else {
					artifactExcludes.Add(d.Value)
				}

			case JavaMavenInstallFile:
				mavenInstallFile = d.Value

			case JavaMavenRepositoryName:
				jvmConfig.MavenLabelPrefix = fmt.Sprintf("@%s//:", d.Value)

			case ScalaForcedTransitiveDeps:
				values := strings.Split(d.Value, " ")
				if len(values) != 2 {
					log.Fatalf(
						"Invalid config for %s directive. Expected 2 values but got %v\n",
						ScalaForcedTransitiveDeps,
						values,
					)
				}

				dep := values[0]
				transitiveDeps := strings.Split(values[1], ",")

				(*jvmConfig.ForcedTransitiveDeps)[dep] = transitiveDeps
			}
		}

		if artifactExcludes != nil {
			jvmConfig.addExcludedArtifacts(artifactExcludes)
		}

		if mavenInstallFile != "" {
			jvmConfig.setMavenInstall(c.RepoRoot, mavenInstallFile)
		}
	}

	if jvmConfig.MavenInstall == nil {
		jvmConfig.setMavenInstall(c.RepoRoot, DEFAULT_MAVEN_INSTALL_FILE)
	}
}
