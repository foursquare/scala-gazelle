# scala-gazelle
A Scala code parser and [Gazelle](https://github.com/bazelbuild/bazel-gazelle) plugin for Bazel build file generation.

## Usage

TODO

## Maintainer notes

### Managing go dependencies

TL;DR:
  1. `go.mod` may be updated by hand, or preferably via `bazel run @io_bazel_rules_go//go get example.com/pkg@version`.
  2. Run `bazel mod tidy` to ensure any changes are reflected in the `go_deps` `use_repo` declaration in `MODULE.bazel`.
  3. Run `bazel run //:gazelle` to update `BUILD` files as needed.

For more details, see the upstream docs from `rules_go` [here](https://github.com/bazel-contrib/rules_go/blob/v0.54.0/docs/go/core/bzlmod.md).

See `bazel run @io_bazel_rules_go//go help get` for the full documentation on `go get`.
