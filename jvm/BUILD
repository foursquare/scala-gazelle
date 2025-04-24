load("@io_bazel_rules_go//go:def.bzl", "go_library")

go_library(
    name = "jvm",
    srcs = [
        "config.go",
        "constants.go",
        "resolve.go",
    ],
    importpath = "github.com/foursquare/scala-gazelle/jvm",
    visibility = ["//visibility:public"],
    deps = [
        "@bazel_gazelle//config",
        "@bazel_gazelle//label",
        "@bazel_gazelle//resolve",
        "@bazel_gazelle//rule",
        "@com_github_emirpasic_gods//sets/treeset",
    ],
)
