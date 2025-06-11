# scala-gazelle
A Scala code parser and [Gazelle](https://github.com/bazelbuild/bazel-gazelle) plugin for Bazel build file generation.

## Usage

TODO

### Installation instructions

Create new `gazelle_binary` and `gazelle` targets in your root `BUILD`/`BUILD.bazel` file (or add the Scala language
plugin to an existing `gazelle_binary` if you wish):

```starlark
gazelle_binary(
    name = "gazelle_bin",
    languages = [
        "@scala_gazelle//scala",
    ],
)

gazelle(
    name = "gazelle",
    args = [
        # "-scala_rules_scala_repo_name=io_bazel_rules_scala", # required with older versions of rules_scala
        # "-scala_parsing_cache_file=...", # beneficial for large repos; specify a .json or .json.gz file path
    ],
    gazelle = ":gazelle_bin",
)
```

#### Bzlmod

TODO: scala_gazelle is not currently published to the Bazel Central Registry. In the meantime, Bzlmod can still be used
via the --override_module flag.

```starlark
bazel_dep(name = "scala_gazelle", version = "0.0.0")
```

Note that building scala_gazelle requires either a rules_go version later than 54.1, or a patch to the go-tree-sitter
module. If upgrading rules_go is not an option for you, you will additionally require the following override in your
`MODULE.bazel` file:

```starlark
go_deps = use_extension("@bazel_gazelle//:extensions.bzl", "go_deps")
go_deps.module_override(
    patches = ["@scala_gazelle//:tree-sitter_cdeps.patch"],
    path = "github.com/smacker/go-tree-sitter",
)
```

#### WORKSPACE

```starlark
SCALA_GAZELLE_VERSION = "fd9ef55674f961f05f339ca576027f706b0f3859"

SCALA_GAZELLE_SHA = "b45ce08742058431f20326cbfe59d240a1f4a644733bfd273f4c35865013b795"

http_archive(
    name = "scala_gazelle",
    strip_prefix = "scala-gazelle-{}".format(SCALA_GAZELLE_VERSION),
    sha256 = SCALA_GAZELLE_SHA,
    url = "https://github.com/foursquare/scala-gazelle/archive/{}.zip".format(SCALA_GAZELLE_VERSION),
)

load("@scala_gazelle//:deps.bzl", "scala_gazelle_deps")

scala_gazelle_deps()
```

Note that with `WORKSPACE` being order dependent, if you get errors building the gazelle binary you may need to move
`scala_gazelle_deps()` earlier in the file to ensure the proper dependency versioning, especially if you use other
Gazelle language plugins.

## Adopting scala-gazelle in an existing repo

 - remove globs
 - add resolve directives for conflicts
 - must refactor to a single library and/or test taret per package

## Maintainer notes

### Managing go dependencies

TL;DR:
  1. `go.mod` may be updated by hand, or preferably via `bazel run @io_bazel_rules_go//go get example.com/pkg@version`.
  2. Run `bazel run @io_bazel_rules_go//go -- mod tidy` to update indirect deps and `go.sum`.
  3. Run `bazel mod tidy` to ensure any changes are reflected in the `go_deps` `use_repo` declaration in `MODULE.bazel`.
  4. Run `bazel run //:gazelle_update_repos` to update the `scala_gazelle_deps` macro in `deps.bzl`
  5. Run `bazel run //:gazelle` to update `BUILD` files as needed.

For more details, see the upstream docs from `rules_go` [here](https://github.com/bazel-contrib/rules_go/blob/v0.54.0/docs/go/core/bzlmod.md).

See `bazel run @io_bazel_rules_go//go help get` for the full documentation on `go get`.
