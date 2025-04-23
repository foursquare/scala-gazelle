load("@rules_go//go:def.bzl", "go_binary", "go_library")

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
        "@com_github_emirpasic_gods//sets/treeset",
        "@com_github_smacker_go_tree_sitter//:go-tree-sitter",
        "@com_github_smacker_go_tree_sitter//scala",
        "@gazelle//config",
        "@gazelle//label",
        "@gazelle//language",
        "@gazelle//repo",
        "@gazelle//resolve",
        "@gazelle//rule",
    ],
)

go_binary(
    name = "parser",
    srcs = ["main.go"],
    visibility = ["//visibility:public"],
    deps = [":scala"],
)
