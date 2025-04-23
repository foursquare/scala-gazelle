load("@io_bazel_rules_go//go:def.bzl", "go_library")

go_library(
    name = "jvm",
    srcs = [
        "config.go",
        "constants.go",
        "resolve.go",
    ],
    importpath = "foursquare/gazelle/jvm",
    visibility = ["//visibility:public"],
    deps = [
        "@bazel_gazelle//config:go_default_library",
        "@bazel_gazelle//label:go_default_library",
        "@bazel_gazelle//resolve:go_default_library",
        "@bazel_gazelle//rule:go_default_library",
        "@com_github_emirpasic_gods//sets/treeset",
    ],
)
