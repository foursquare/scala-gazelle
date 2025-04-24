load("@io_bazel_rules_go//go:def.bzl", "go_binary", "go_library")

go_library(
    name = "scala",
    srcs = [
        "config.go",
        "constants.go",
        "lang.go",
        "parser.go",
    ],
    importpath = "github.com/foursquare/scala-gazelle/scala",
    visibility = ["//visibility:public"],
    deps = [
        "//jvm",
        "//parse",
        "@bazel_gazelle//config",
        "@bazel_gazelle//label",
        "@bazel_gazelle//language",
        "@bazel_gazelle//repo",
        "@bazel_gazelle//resolve",
        "@bazel_gazelle//rule",
        "@com_github_emirpasic_gods//sets/treeset",
        "@com_github_smacker_go_tree_sitter//:go-tree-sitter",
        "@com_github_smacker_go_tree_sitter//scala",
    ],
)

go_binary(
    name = "parser",
    srcs = ["main.go"],
    visibility = ["//visibility:public"],
    deps = [":scala"],
)
