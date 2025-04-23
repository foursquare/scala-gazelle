load("@io_bazel_rules_go//go:def.bzl", "go_binary", "go_library")

go_library(
    name = "scala",
    srcs = [
        "config.go",
        "constants.go",
        "lang.go",
        "parser.go",
    ],
    importpath = "foursquare/gazelle/scala",
    visibility = ["//visibility:public"],
    deps = [
        "//src/go/src/foursquare/gazelle/jvm",
        "//src/go/src/foursquare/gazelle/parse",
        "@bazel_gazelle//config:go_default_library",
        "@bazel_gazelle//label:go_default_library",
        "@bazel_gazelle//language:go_default_library",
        "@bazel_gazelle//repo:go_default_library",
        "@bazel_gazelle//resolve:go_default_library",
        "@bazel_gazelle//rule:go_default_library",
        "@com_github_bazelbuild_buildtools//build:go_default_library",
        "@com_github_emirpasic_gods//sets/treeset",
        "@com_github_emirpasic_gods//utils",
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
