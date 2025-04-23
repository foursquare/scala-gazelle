package main

import (
	"archive/zip"
	"encoding/json"
	"flag"
	"fmt"
	"io/ioutil"
	"os"
	"path/filepath"
	"runtime/pprof"
	"strings"

	"foursquare/gazelle/scala"
)

// Container for file path arguments
type filePathsArg []string

// Implement the flag.Value interface
func (filePaths *filePathsArg) String() string {
	return strings.Join(*filePaths, ",")
}

func (filePaths *filePathsArg) Set(filePath string) error {
	if strings.ContainsRune(filePath, ',') {
		newFiles := strings.Split(filePath, ",")
		*filePaths = append(*filePaths, newFiles...)
	} else {
		*filePaths = append(*filePaths, filePath)
	}

	return nil
}

func main() {
	var filePaths filePathsArg
	flag.Var(
		&filePaths,
		"file_path",
		"Path or paths to the Scala file(s) or .srcjar to parse",
	)
	outputDir := flag.String(
		"output_dir",
		"",
		"Generate json files for parsed symbol information instead of printing to stdout",
	)
	verboseTreeSitterErrors := flag.Bool(
		"verbose",
		false,
		"Enable verbose tree-sitter parsing errors",
	)
	debug := flag.Bool(
		"debug",
		false,
		"Print out detailed parsing information",
	)
	dedupeParsing := flag.Bool(
		"dedupe_parsing",
		false,
		"Error if the parser tries to examine the same AST node multiple times",
	)
	cpuprofile := flag.String(
		"cpuprofile",
		"",
		"Generate a cpu profile while parsing and write it to the given file",
	)
	flag.Parse()

	if *cpuprofile != "" {
		f, err := os.Create(*cpuprofile)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error creating cpu profile file:\n%s\n", err)
			os.Exit(1)
		}
		pprof.StartCPUProfile(f)
		defer pprof.StopCPUProfile()
	}

	handleFile := func(sourceString string, filePath string) {
		parser := scala.NewParser(*debug, *verboseTreeSitterErrors, *dedupeParsing)

		parseResult, errs := parser.Parse(filePath, sourceString)
		if len(errs) != 0 {
			fmt.Fprintf(os.Stderr, "Parse errors in %s:\n", filePath)
			for _, err := range errs {
				fmt.Fprintln(os.Stderr, err)
			}
			os.Exit(1)
		}

		bytes, err := json.MarshalIndent(parseResult, "", "    ")
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error encoding json for %s:\n%s\n", filePath, err)
			os.Exit(1)
		}

		if *outputDir != "" {
			outputFilePath := filepath.Join(*outputDir, filepath.Base(filePath))
			outputFile, err := os.Create(outputFilePath)
			if err != nil {
				fmt.Fprintf(os.Stderr, "Error opening %s for writing:\n%s\n", outputFilePath, err)
				os.Exit(1)
			}
			defer outputFile.Close()

			_, err = outputFile.Write(bytes)
			if err != nil {
				fmt.Fprintf(os.Stderr, "Error writing to %s:\n%s\n", outputFilePath, err)
				os.Exit(1)
			}

		} else {
			os.Stdout.Write(bytes)
			fmt.Println()
		}
	}

	for _, filePath := range filePaths {
		fileExt := filepath.Ext(filePath)

		if fileExt == ".scala" {
			fileBytes, err := os.ReadFile(filePath)
			if err != nil {
				fmt.Fprintf(os.Stderr, "Error reading source file %s:\n%s\n", filePath, err)
				os.Exit(1)
			}
			sourceString := string(fileBytes)

			handleFile(sourceString, filePath)

		} else if fileExt == ".srcjar" {
			srcjarReader, err := zip.OpenReader(filePath)
			if err != nil {
				fmt.Fprintf(os.Stderr, "Error opening %s for reading:\n%s\n", filePath, err)
				os.Exit(1)
			}
			defer srcjarReader.Close()

			for _, srcFile := range srcjarReader.File {
				if srcFile.FileInfo().IsDir() {
					continue
				}

				srcPath := fmt.Sprintf("%s!%s", filePath, srcFile.Name)

				reader, err := srcFile.Open()
				if err != nil {
					fmt.Fprintf(os.Stderr, "Error opening zipped source file %s:\n%s\n", srcPath, err)
					os.Exit(1)
				}
				defer reader.Close()

				srcFileBytes, err := ioutil.ReadAll(reader)
				if err != nil {
					fmt.Fprintf(os.Stderr, "Error reading zipped source file %s:\n%s\n", srcPath, err)
					os.Exit(1)
				}
				sourceString := string(srcFileBytes)

				handleFile(sourceString, srcPath)
			}

		} else {
			fmt.Fprintf(os.Stderr, "Expected .scala file or .srcjar, found: %s\n", filePath)
			os.Exit(1)
		}
	}
}
