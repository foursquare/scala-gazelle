package parse

import (
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log"
	"os"
	"path/filepath"
)

var computedGazelleChecksum *string = nil

// Gazelle does not run through Bazel, so we roll our own cache fingerprinting. This is
// done though a combination of taking the sha256 checksums of the Gazelle binary we're
// running out of and each file we parse.
func gazelleChecksum() string {
	if computedGazelleChecksum == nil {
		executablePath, err := os.Executable()
		if err != nil {
			log.Fatalf("Error reading executable path: %s\n", err)
		}

		if resolvedPath, err := filepath.EvalSymlinks(executablePath); err == nil {
			executablePath = resolvedPath
		}

		executableBytes, err := os.ReadFile(executablePath)
		if err != nil {
			log.Fatalf(
				"Error reading gazelle executable '%s' for fingerprinting:\n%s\n",
				executablePath,
				err,
			)
		}

		checksumBytes := sha256.Sum256(executableBytes)
		checksum := hex.EncodeToString(checksumBytes[:])
		computedGazelleChecksum = &checksum
	}

	return *computedGazelleChecksum
}

type untypedParsingCache struct {
	GazelleBinaryChecksum string                  `json:"gazelle_binary_checksum"`
	Cache                 *map[string]interface{} `json:"parse_cache"`
}

type ParsingCache[ParseResult any] struct {
	GazelleBinaryChecksum string                   `json:"gazelle_binary_checksum"`
	Cache                 *map[string]*ParseResult `json:"parse_cache"`
}

// Implemented by language-specific parsers
type CacheableParser[ParseResult any] interface {
	Parse(filePath string, sourceString string) (*ParseResult, []error)
	UnmarshalParsingCache(*map[string]*ParseResult, *map[string]interface{})
}

// Parent interface implemented by the cached/uncached wrapper types here.
type Parser[ParseResult any] interface {
	ParseFile(filePath string) (*ParseResult, []error)
	WriteParsingCache()
}

type CachingParser[ParseResult any] struct {
	Parser[ParseResult]

	parser           CacheableParser[ParseResult]
	parsingCache     ParsingCache[ParseResult]
	parsingCacheFile string
}

func loadParsingCache[ParseResult any](
	parser CacheableParser[ParseResult],
	parsingCacheFile string,
) ParsingCache[ParseResult] {
	cacheMap := make(map[string]*ParseResult, 0)
	parsingCache := ParsingCache[ParseResult]{
		GazelleBinaryChecksum: gazelleChecksum(),
		Cache:                 &cacheMap,
	}

	var cacheReader io.Reader

	cacheFile, err := os.Open(parsingCacheFile)
	if err != nil {
		if os.IsNotExist(err) {
			log.Printf(
				"WARN: parsing cache file '%s' does not exist. It will be created.\n",
				parsingCacheFile,
			)
			return parsingCache

		} else {
			log.Fatalf("Error opening parsing cache file %s:\n%s\n", parsingCacheFile, err)
		}
	}
	cacheReader = cacheFile
	defer cacheFile.Close()

	if filepath.Ext(parsingCacheFile) == ".gz" {
		gzipReader, err := gzip.NewReader(cacheReader)
		if err != nil {
			log.Fatalf("Error decoding gzipped cache file %s:\n%s\n", parsingCacheFile, err)
		}
		cacheReader = gzipReader
		defer gzipReader.Close()
	}

	var untypedCache untypedParsingCache
	err = json.NewDecoder(cacheReader).Decode(&untypedCache)
	if err != nil {
		log.Fatalf("Unable to parse parsing cache file %s:\n%s\n", parsingCacheFile, err)
	}

	if parsingCache.GazelleBinaryChecksum != untypedCache.GazelleBinaryChecksum {
		log.Printf(
			"WARN: Computed Gazelle binary checksum %s does not match cache file checksum "+
				"%s from %s. The cache file will be regenerated.",
			parsingCache.GazelleBinaryChecksum,
			untypedCache.GazelleBinaryChecksum,
			parsingCacheFile,
		)

	} else {
		parser.UnmarshalParsingCache(parsingCache.Cache, untypedCache.Cache)
	}

	return parsingCache
}

func NewCachingParser[ParseResult any](
	parser CacheableParser[ParseResult],
	parsingCacheFile string,
) CachingParser[ParseResult] {
	return CachingParser[ParseResult]{
		parser:           parser,
		parsingCache:     loadParsingCache(parser, parsingCacheFile),
		parsingCacheFile: parsingCacheFile,
	}
}

func (cp *CachingParser[ParseResult]) ParseFile(filePath string) (*ParseResult, []error) {
	fileBytes, err := os.ReadFile(filePath)
	if err != nil {
		log.Fatalf("Error reading source file %s:\n%s\n", filePath, err)
	}

	hashBytes := sha256.Sum256(fileBytes)
	hash := hex.EncodeToString(hashBytes[:])

	if cachedParse, exists := (*cp.parsingCache.Cache)[hash]; exists {
		// file has not changed, return cached result
		return cachedParse, nil
	}

	sourceString := string(fileBytes)
	parseResult, errs := cp.parser.Parse(filePath, sourceString)
	if errs == nil || len(errs) == 0 {
		(*cp.parsingCache.Cache)[hash] = parseResult
	}

	return parseResult, errs
}

func (cp *CachingParser[ParseResult]) WriteParsingCache() {
	cacheFileDir := filepath.Dir(cp.parsingCacheFile)
	if _, err := os.Stat(cacheFileDir); os.IsNotExist(err) {
		err = os.MkdirAll(cacheFileDir, 0755)
		if err != nil {
			log.Fatalf("Error creating parent directory of parsing cache file:\n%s\n", err)
		}
	}

	var cacheWriter io.Writer

	cacheFile, err := os.Create(cp.parsingCacheFile)
	if err != nil {
		log.Fatalf(
			"Error opening parsing cache file %s for writing:\n%s\n",
			cp.parsingCacheFile,
			err,
		)
	}
	cacheWriter = cacheFile
	defer cacheFile.Close()

	if filepath.Ext(cp.parsingCacheFile) == ".gz" {
		gzipWriter := gzip.NewWriter(cacheWriter)
		if err != nil {
			log.Fatalf("Error decoding gzipped cache file %s:\n%s\n", cp.parsingCacheFile, err)
		}
		cacheWriter = gzipWriter
		defer gzipWriter.Close()
	}

	jsonEncoder := json.NewEncoder(cacheWriter)
	jsonEncoder.SetIndent("", "    ")
	err = jsonEncoder.Encode(cp.parsingCache)
	if err != nil {
		log.Fatal("Error writing parsing cache to disk:\n%s\n", err)
	}
}

type UncachedParser[ParseResult any] struct {
	Parser[ParseResult]

	parser CacheableParser[ParseResult]
}

func NewUncachedParser[ParseResult any](
	parser CacheableParser[ParseResult],
) UncachedParser[ParseResult] {
	return UncachedParser[ParseResult]{
		parser: parser,
	}
}

func (up *UncachedParser[ParseResult]) ParseFile(filePath string) (*ParseResult, []error) {
	fileBytes, err := os.ReadFile(filePath)
	if err != nil {
		log.Fatalf("Error reading source file %s:\n%s\n", filePath, err)
	}

	sourceString := string(fileBytes)
	return up.parser.Parse(filePath, sourceString)
}

func (up *UncachedParser[ParseResult]) WriteParsingCache() {
}
