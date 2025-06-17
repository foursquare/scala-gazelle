package jvm

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"strings"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/label"
	"github.com/bazelbuild/bazel-gazelle/resolve"
	"github.com/emirpasic/gods/sets/treeset"
)

// ArtifactLabels: maven deps viable for resolve mapping
// PackageMapping: package -> set of providing BUILD labels
type MavenInstallData struct {
	ArtifactLabels *treeset.Set
	PackageMapping map[string]*treeset.Set
}

func jarToLabel(jarOrJarPath string, mavenLabelPrefix string) string {
	rewritten := strings.NewReplacer(
		// Jars with classifiers show up as "com.twitter:finatra-http_2.12:jar:tests",
		// but want to end up as "@maven//:com_twitter_finatra_http_2_12_tests".
		":jar:", "_",
		".", "_",
		"-", "_",
		":", "_",
		"/", "_",
	).Replace(jarOrJarPath)

	return mavenLabelPrefix + rewritten
}

var mavenInstallCache map[string]*MavenInstallData = make(map[string]*MavenInstallData)

func ParseMavenInstall(
	path string,
	mavenLabelPrefix string,
	artifactExcludes *treeset.Set,
) *MavenInstallData {
	if mavenInstallData, exists := mavenInstallCache[path]; exists {
		return mavenInstallData
	}

	file, err := os.Open(path)
	if err != nil {
		log.Fatalf("Error opening maven_install.json: %s\n", err)
	}
	defer file.Close()

	var installJSON map[string]interface{}
	if err := json.NewDecoder(file).Decode(&installJSON); err != nil {
		log.Fatalf("Error reading maven_install.json: %s\n", err)
	}

	artifacts := treeset.NewWithStringComparator()
	inversed := make(map[string]*treeset.Set)
	for artifact, artifactData := range installJSON["artifacts"].(map[string]interface{}) {
		for classifier := range artifactData.(map[string]interface{})["shasums"].(map[string]interface{}) {
			classifiedArtifact := artifact
			if classifier == "sources" {
				// There are technically source jars which contain compiled classfiles, but there
				// is probably no situation in which depending on them is correct.
				continue

			} else if classifier != "jar" {
				classifiedArtifact = fmt.Sprintf("%s:jar:%s", classifiedArtifact, classifier)
			}

			if packages, ok := installJSON["packages"].(map[string]interface{})[classifiedArtifact]; ok {
				label := jarToLabel(classifiedArtifact, mavenLabelPrefix)
				if artifactExcludes.Contains(label) {
					continue
				}

				// TODO(jacob): When using `strict_visibility = True` with rules_jvm_external,
				//		the lockfile still contains transitive jars that are not actually usable as
				//		dependencies (they generate with private visibility). We could query for
				//		viable labels via `attr(visibility, //visibility:public, kind(jvm_import, @maven//:all))`,
				//		but that is potentially slow so instead we just ignore conflicting labels
				//		manually. It would be nice to have an automated solution here though.
				artifacts.Add(label)

				for _, pkg := range packages.([]interface{}) {
					packageName := pkg.(string)
					if _, exists := inversed[packageName]; !exists {
						inversed[packageName] = treeset.NewWithStringComparator()
					}
					inversed[packageName].Add(label)
				}
			}
		}
	}

	for pkg, mavenLabels := range DEFAULT_PACKAGE_MAP {
		// parsed maven package map takes priority over defaults
		if _, exists := inversed[pkg]; !exists {
			inversed[pkg] = mavenLabels
		}
	}

	mavenInstallData := &MavenInstallData{
		ArtifactLabels: artifacts,
		PackageMapping: inversed,
	}
	mavenInstallCache[path] = mavenInstallData
	return mavenInstallData
}

func forcedTransitiveDepsForDep(
	forcedDepsMap *map[string][]string,
	symbolLabel string,
) *treeset.Set {
	forcedDeps := treeset.NewWithStringComparator(symbolLabel)

	// TODO(jacob): Use an actual stack implementation
	toCheck := []string{symbolLabel}
	for len(toCheck) > 0 {
		nextDep := toCheck[len(toCheck)-1]
		toCheck = toCheck[:len(toCheck)-1]

		if transitiveDeps, ok := (*forcedDepsMap)[nextDep]; ok {
			// TODO(jacob): check for previously seen deps to guard against erroneous cycles
			for _, transitiveDep := range transitiveDeps {
				toCheck = append(toCheck, transitiveDep)
				forcedDeps.Add(transitiveDep)
			}
		}
	}

	return forcedDeps
}

func isSymbol(name string) bool {
	// Blindly assume the given name is a symbol and not a package if it isn't lowercased.
	return name != strings.ToLower(name)
}

func lookUpSymbol(
	c *config.Config,
	ruleIndex *resolve.RuleIndex,
	lang string,
	symbol string,
) []label.Label {
	importSpec := resolve.ImportSpec{
		Lang: lang,
		Imp:  symbol,
	}

	// TODO(jacob): Add resolve logic to always walk back and check the entire symbol
	//	namespace against this map, so that e.g. we can just list org.apache.thrift once
	//	rather than having to list all its sub-packages individually.
	if overrideLabel, exists := resolve.FindRuleWithOverride(c, importSpec, lang); exists {
		return []label.Label{overrideLabel}
	}

	// NOTE(jacob): CrossResolve functions for other languages are called here via
	//		FindRulesByImportWithConfig.
	matches := ruleIndex.FindRulesByImportWithConfig(c, importSpec, lang)
	labels := make([]label.Label, len(matches))

	for i, match := range matches {
		labels[i] = match.Label
	}

	return labels
}

func ResolveJvmSymbols(
	c *config.Config,
	ruleIndex *resolve.RuleIndex,
	from label.Label,
	lang string,
	usedSymbols *treeset.Set,
) *treeset.Set {
	jvmConfig := JvmConfigForConfig(c, from.Pkg)
	deps := treeset.NewWithStringComparator()

	addDep := func(dep string) {
		if !jvmConfig.excludedArtifacts.Contains(dep) {
			forcedDeps := forcedTransitiveDepsForDep(jvmConfig.ForcedTransitiveDeps, dep)
			deps = deps.Union(forcedDeps)
		}
	}

	usedSymbolsIter := usedSymbols.Iterator()
	for usedSymbolsIter.Next() {
		symbol := usedSymbolsIter.Value().(string)
		originalSymbol := symbol

		// Remove absolute path prefix in Scala imports.
		symbol = strings.TrimPrefix(symbol, "_root_.")
		// Wildcard imports are not in the symbol map explicitly.
		symbol = strings.TrimSuffix(symbol, "._")

		var labels []label.Label
		var mavenLabels *treeset.Set
		var packageExists bool

		runLookupWithFallback := func(skipIsSymbolCheck bool) {
			if labels = lookUpSymbol(c, ruleIndex, lang, symbol); len(labels) == 0 {
				mavenLabels, packageExists = jvmConfig.MavenInstall.PackageMapping[symbol]
				if !packageExists && strings.Contains(symbol, ".") {
					lastDotIndex := strings.LastIndex(symbol, ".")
					if skipIsSymbolCheck || isSymbol(symbol[lastDotIndex+1:]) {
						symbol = symbol[:lastDotIndex]
					}
				}
			}
		}

		// Happy path: our first lookup here succeeds without any further help. This generally
		// includes most imports of in-repo classes or imports of packages.
		//
		// We always fall back to peeling off the last node of the symbol here in the event of an
		// unsuccessful lookup, to ensure imports of functions or variables will fall back to
		// looking up their containing scope.
		runLookupWithFallback(true)

		// Second try: we look up the containing scope of the original symbol. This catches most
		// imports of symbols from maven jars, which are indexed at the package level.
		if len(labels) == 0 && !packageExists {
			runLookupWithFallback(false)
		}

		// One final go... catches cases like org.jboss.netty.buffer.ChannelBuffers.copiedBuffer
		// where we might import a nested symbol from a jar which only exists in the maven package
		// mapping -- we have to whittle down to org.jboss.netty.buffer before finding a match.
		//
		// Note that we always perform a final package match lookup even if we found a providing
		// label in the rule index, as maven jars take precedence over in-repo targets where
		// package namespace shadowing is concerned.
		if !packageExists {
			if len(labels) == 0 {
				labels = lookUpSymbol(c, ruleIndex, lang, symbol)
			}
			mavenLabels, packageExists = jvmConfig.MavenInstall.PackageMapping[symbol]
		}

		if len(labels) > 1 {
			var b strings.Builder
			fmt.Fprintf(
				&b,
				"Error during resolve for %s (%s): used symbol '%s' appears to have "+
					"multiple definitions in the following targets:\n",
				from,
				lang,
				symbol,
			)
			for _, symbolLabel := range labels {
				fmt.Fprintf(&b, "%s\n", symbolLabel)
			}
			log.Fatalf(b.String())

		} else if len(labels) == 1 && (!packageExists ||
			mavenLabels.Contains(labels[0].String()) ||
			jvmConfig.excludedArtifacts.Contains(labels[0].String())) {

			symbolLabel := labels[0]
			// don't add self-dependencies
			if from != symbolLabel {
				addDep(symbolLabel.String())
			}

		} else if packageExists {
			visibleLabels := mavenLabels.Select(func(index int, value interface{}) bool {
				return jvmConfig.MavenInstall.ArtifactLabels.Contains(value)
			})

			if visibleLabels.Size() == 1 {
				addDep(visibleLabels.Values()[0].(string))

			} else if visibleLabels.Size() > 1 {
				log.Fatalf(
					"Error during resolve for %s (%s): %s (reduced from %s) was not present in "+
						"the rule index but is provided by more than one maven jar, please add "+
						"a resolve directive for either the package or the original symbol to "+
						"one of these labels: %v\n",
					from,
					lang,
					symbol,
					originalSymbol,
					visibleLabels.Values(),
				)

			} else {
				log.Fatalf(
					"Error during resolve for %s (%s): %s is provided by at least one maven "+
						"jar, but none of them were visible. This probably means you are "+
						"importing from a transitive dependency and need to add it to the maven "+
						"install so it can be used directly: %v\n",
					from,
					lang,
					symbol,
					mavenLabels.Values(),
				)
			}

		} else {
			// Garbage or otherwise unresolvable symbol.
		}
	}

	return deps
}
