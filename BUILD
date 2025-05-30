load("@bazel_gazelle//:def.bzl", "gazelle")

# gazelle:prefix github.com/foursquare/scala-gazelle
# gazelle:exclude bazel-bin
# gazelle:exclude bazel-out
# gazelle:exclude bazel-scala-gazelle
# gazelle:exclude bazel-testlogs
gazelle(name = "gazelle")

gazelle(
    name = "gazelle_update_repos",
    args = [
        "-from_file=go.mod",
        "-to_macro=deps.bzl%scala_gazelle_deps",
        "-prune",
    ],
    command = "update-repos",
)
