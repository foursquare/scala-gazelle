package scala

import (
	"flag"
	"log"
	"path"
	"path/filepath"
	"strings"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/language"
	"github.com/bazelbuild/bazel-gazelle/rule"

	"github.com/emirpasic/gods/sets/treeset"

	"github.com/foursquare/scala-gazelle/jvm"
	"github.com/foursquare/scala-gazelle/parse"
)

const (
	// ScalaTestFileSuffixes indicates within a test directory which files are test
	// classes vs utility classes, based on their basename. It should be set up to match
	// the value used for the test rules' suffixes attribute if applicable, with the
	// '.scala' file extensions added.
	//
	// Accepted values are a comma-delimited list of strings.
	//
	// Defaults to DEFAULT_SCALA_TEST_FILE_SUFFIXES.
	ScalaTestFileSuffixes = "scala_test_file_suffixes"

	// ScalaTestFramework indicates whether scalatest or junit test rules should be
	// generated. Note that setting this to "junit" will cause the Scala plugin to set
	// the test rule's 'suffixes' attribute; if this is something you handle via a macro
	// wrapper, you may wish to set this to "scalatest" and use '# gazelle:map_kind'
	// to convert to the macro instead.
	//
	// Accepted values are either "scalatest" or "junit".
	//
	// Defaults to "scalatest".
	ScalaTestFramework = "scala_test_framework"

	// If ScalaWarnTestRuleMismatch is set to true, the Scala language plugin will output
	// a warning when an existing non-test rule would contain source files matching the
	// configured test file suffixes. This can help avoid human error when unit tests are
	// accidentally added to a library rule, in which case the tests will silently never
	// run. But this can also be noisy so you may wish to disable it.
	//
	// Defaults to true.
	ScalaWarnTestRuleMismatch = "scala_warn_test_rule_mismatch"
)

type scalaTestFrameworkType string

const (
	SCALA_JUNIT_FRAMEWORK     scalaTestFrameworkType = "junit"
	SCALA_SCALATEST_FRAMEWORK scalaTestFrameworkType = "scalatest"
)

func ScalaTestFrameworkType(value string) scalaTestFrameworkType {
	switch scalaTestFrameworkType(value) {
	case SCALA_JUNIT_FRAMEWORK:
		return SCALA_JUNIT_FRAMEWORK
	case SCALA_SCALATEST_FRAMEWORK:
		return SCALA_SCALATEST_FRAMEWORK
	default:
		log.Fatalf(
			"Invalid value for %s directive: %s. Accepted values are either %s or %s",
			ScalaTestFramework,
			value,
			SCALA_JUNIT_FRAMEWORK,
			SCALA_SCALATEST_FRAMEWORK,
		)
		panic("unreachable")
	}
}

func (t scalaTestFrameworkType) Kind() string {
	if t == SCALA_JUNIT_FRAMEWORK {
		return SCALA_JUNIT_TEST_KIND
	} else {
		return SCALA_TEST_KIND
	}
}

func (t scalaTestFrameworkType) String() string {
	return string(t)
}

// ScalaConfig represents a config extension for a specific Bazel package.
type ScalaConfig struct {
	ScalaTestFileSuffixes *[]string
	ScalaTestKind         string
	WarnTestRuleMismatch  bool
}

func NewScalaConfig() *ScalaConfig {
	return &ScalaConfig{
		ScalaTestFileSuffixes: &DEFAULT_SCALA_TEST_FILE_SUFFIXES,
		ScalaTestKind:         SCALA_TEST_KIND,
		WarnTestRuleMismatch:  true,
	}
}

// NewChild creates a new child ScalaConfig. It inherits desired values from the
// current ScalaConfig.
func (c *ScalaConfig) NewChild() *ScalaConfig {
	return &ScalaConfig{
		ScalaTestFileSuffixes: c.ScalaTestFileSuffixes,
		ScalaTestKind:         c.ScalaTestKind,
		WarnTestRuleMismatch:  c.WarnTestRuleMismatch,
	}
}

func (c *ScalaConfig) IsScalaTestFile(filename string) bool {
	for _, suffix := range *c.ScalaTestFileSuffixes {
		if strings.HasSuffix(filename, suffix) {
			return true
		}
	}
	return false
}

// Resolves aliases and kind mappings and returns whether the given kind is a variant of
// kindToCheckAgainst. Assumes only one layer of indirection in alias/kind mapping.
func isKind(c *config.Config, kind string, kindToCheckAgainst string) bool {
	aliases := []string{kind}
	if from, exists := c.AliasMap[kind]; exists {
		aliases = append(aliases, from)
	}

	mappedKinds := []string{kindToCheckAgainst}
	if mappedKind, exists := c.KindMap[kindToCheckAgainst]; exists {
		mappedKinds = append(mappedKinds, mappedKind.KindName)
	}

	for _, alias := range aliases {
		for _, mappedKind := range mappedKinds {
			if alias == mappedKind {
				return true
			}
		}
	}

	return false
}

// Resolves aliases and kind mappings and returns whether the given kind is a macro kind
// or not. Assumes only one layer of indirection in alias/kind mapping.
func (c *ScalaConfig) IsScalaMacroKind(generalConfig *config.Config, kind string) bool {
	return isKind(generalConfig, kind, SCALA_MACRO_KIND)
}

// Resolves aliases and kind mappings and returns whether the given kind is a test kind
// or not. Assumes only one layer of indirection in alias/kind mapping.
func (c *ScalaConfig) IsScalaTestKind(generalConfig *config.Config, kind string) bool {
	return isKind(generalConfig, kind, c.ScalaTestKind)
}

// ScalaConfigs is an extension of map[string]*ScalaConfig. It provides finding methods
// on top of the mapping.
type ScalaConfigs map[string]*ScalaConfig

// ParentForPackage returns the parent ScalaConfig for the given Bazel package.
func (c *ScalaConfigs) ParentForPackage(pkg string) *ScalaConfig {
	dir := path.Dir(pkg)
	if dir == "." {
		dir = ""
	}
	parent := (map[string]*ScalaConfig)(*c)[dir]
	return parent
}

func ScalaConfigForConfig(c *config.Config, pkg string) *ScalaConfig {
	scalaConfigs := c.Exts[LANGUAGE_NAME].(*ScalaConfigs)
	return (*scalaConfigs)[pkg]
}

func ScalaConfigForArgs(args language.GenerateArgs) *ScalaConfig {
	return ScalaConfigForConfig(args.Config, args.Rel)
}

// ScalaConfigurer satisfies the config.Configurer interface. It's the
// language-specific configuration extension.
//
// See config.Configurer for more information.
type ScalaConfigurer struct {
	*jvm.JvmConfigurer

	lang                      *scalaLang
	unparsedCrossResolveLangs string

	CrossResolveLangs  *treeset.Set
	ParsingCacheFile   string
	RulesScalaRepoName string
}

func NewScalaConfigurer(lang *scalaLang) *ScalaConfigurer {
	return &ScalaConfigurer{
		JvmConfigurer:     jvm.NewJvmConfigurer(),
		lang:              lang,
		CrossResolveLangs: treeset.NewWithStringComparator(),
	}
}

func (sc *ScalaConfigurer) getOrInitScalaConfigs(c *config.Config) *ScalaConfigs {
	if _, exists := c.Exts[LANGUAGE_NAME]; !exists {
		scalaConfigs := ScalaConfigs{
			"": NewScalaConfig(),
		}
		c.Exts[LANGUAGE_NAME] = &scalaConfigs
	}

	return c.Exts[LANGUAGE_NAME].(*ScalaConfigs)
}

func (sc *ScalaConfigurer) RegisterFlags(fs *flag.FlagSet, cmd string, c *config.Config) {
	sc.JvmConfigurer.RegisterFlags(fs, cmd, c)

	fs.StringVar(
		&sc.unparsedCrossResolveLangs,
		"scala_cross_resolve_langs",
		"",
		"When specified, indicates which languages the scala language plugin should "+
			"attempt to CrossResolve imports for. Accepted values are a comma-delimited "+
			"list of strings.",
	)

	fs.StringVar(
		&sc.ParsingCacheFile,
		"scala_parsing_cache_file",
		"",
		"When specified, symbol parsing will generate and update a json file on disk "+
			"at the given location. Specify a .gz file extension to enable gzipping of the "+
			"json cache file.",
	)

	fs.StringVar(
		&sc.RulesScalaRepoName,
		"scala_rules_scala_repo_name",
		DEFAULT_RULES_SCALA_REPO_NAME,
		"Specifies the default rules_scala repo name used for kind imports. In older "+
			"rules_scala versions, this was required to be 'io_bazel_rules_scala', but "+
			"this is no longer the case and the getting started docs now recommend "+
			"'rules_scala'. See https://github.com/bazelbuild/rules_scala/pull/1696 "+
			"for details.",
	)
}

func (sc *ScalaConfigurer) CheckFlags(fs *flag.FlagSet, c *config.Config) error {
	if sc.unparsedCrossResolveLangs != "" {
		for _, lang := range strings.Split(sc.unparsedCrossResolveLangs, ",") {
			sc.CrossResolveLangs.Add(lang)
		}
	}

	// TODO: wire up parser debug params
	parser := NewParser(false, false, false)
	if sc.ParsingCacheFile != "" {
		if !filepath.IsAbs(sc.ParsingCacheFile) {
			sc.ParsingCacheFile = filepath.Join(c.RepoRoot, sc.ParsingCacheFile)
		}

		wrappedParser := parse.NewCachingParser[ParseResult](
			parser,
			sc.ParsingCacheFile,
		)
		sc.lang.parser = &wrappedParser

	} else {
		wrappedParser := parse.NewUncachedParser[ParseResult](parser)
		sc.lang.parser = &wrappedParser
	}

	return sc.JvmConfigurer.CheckFlags(fs, c)
}

func (sc *ScalaConfigurer) KnownDirectives() []string {
	return append(
		sc.JvmConfigurer.KnownDirectives(),
		ScalaTestFileSuffixes,
		ScalaTestFramework,
		ScalaWarnTestRuleMismatch,
	)
}

func (sc *ScalaConfigurer) Configure(c *config.Config, rel string, f *rule.File) {
	sc.JvmConfigurer.Configure(c, rel, f)

	scalaConfigs := sc.getOrInitScalaConfigs(c)
	scalaConfig, exists := (*scalaConfigs)[rel]
	if !exists {
		parent := scalaConfigs.ParentForPackage(rel)
		scalaConfig = parent.NewChild()
		(*scalaConfigs)[rel] = scalaConfig
	}

	if f != nil {
		for _, d := range f.Directives {
			switch d.Key {
			case ScalaTestFileSuffixes:
				newSuffixes := strings.Split(d.Value, ",")

				var filteredSuffixes []string
				for _, newSuffix := range newSuffixes {
					newSuffix = strings.TrimSpace(newSuffix)
					if newSuffix != "" {
						filteredSuffixes = append(filteredSuffixes, newSuffix)
					}
				}

				scalaConfig.ScalaTestFileSuffixes = &filteredSuffixes

			case ScalaTestFramework:
				kind := ScalaTestFrameworkType(d.Value).Kind()
				scalaConfig.ScalaTestKind = kind

			case ScalaWarnTestRuleMismatch:
				if strings.ToLower(d.Value) == "false" {
					scalaConfig.WarnTestRuleMismatch = false
				} else {
					scalaConfig.WarnTestRuleMismatch = true
				}
			}
		}
	}
}
