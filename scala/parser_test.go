package scala

import (
	"encoding/json"
	"io/ioutil"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/foursquare/scala-gazelle/parse"
)

func TestParserIntegration(t *testing.T) {
	parser := parse.NewUncachedParser[ParseResult](NewParser(false, false, false))

	testFiles := []string{
		filepath.Join("fsqio", "Lists"),
		filepath.Join("fsqio", "Query"),
		filepath.Join("fsqio", "TrivialORMQueryTest"),
		filepath.Join("scalac", "Global"),
		filepath.Join("scalac", "Implicits"),
		filepath.Join("scalac", "Namers"),
		filepath.Join("spark", "AgnosticEncoder"),
		filepath.Join("spark", "GeneralizedLinearRegression"),
		filepath.Join("spark", "SparkSession"),
	}

	for _, file := range testFiles {
		t.Run("parser integration test with "+file, func(t *testing.T) {
			noExtPath := filepath.Join("testdata", "parser_integration", file)
			parseResult, errs := parser.ParseFile(noExtPath + ".scala")

			if errs != nil && len(errs) != 0 {
				for _, err := range errs {
					t.Log(err)
				}
				t.Fail()
			}

			actualJsonBytes, err := json.Marshal(parseResult)
			if err != nil {
				t.Error(err)
			}
			actualJson := string(actualJsonBytes)

			expectedJsonBytes, err := ioutil.ReadFile(noExtPath + ".json")
			if err != nil {
				t.Error(err)
			}
			expectedJson := string(expectedJsonBytes)

			require.JSONEq(t, expectedJson, actualJson)
		})
	}
}
