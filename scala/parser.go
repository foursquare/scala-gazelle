package scala

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"os"
	"regexp"
	"strings"

	"github.com/emirpasic/gods/sets/treeset"
	sitter "github.com/smacker/go-tree-sitter"
	"github.com/smacker/go-tree-sitter/scala"

	"github.com/foursquare/scala-gazelle/parse"
)

// Parser originally modelled off Aspect's Kotlin parser here:
// https://github.com/aspect-build/aspect-cli/tree/v1.509.25/gazelle/kotlin

// Scala tree-sitter grammar:
// https://github.com/tree-sitter/tree-sitter-scala/blob/master/src/node-types.json

type SymbolData struct {
	FullyQualifiedNames *treeset.Set `json:"fully_qualified_names"`
	ExportedSymbols     *treeset.Set `json:"symbols"`
}

func EmptySymbolData() *SymbolData {
	return &SymbolData{
		FullyQualifiedNames: treeset.NewWithStringComparator(),
		ExportedSymbols:     treeset.NewWithStringComparator(),
	}
}

func SingleNameData(name string) *SymbolData {
	return &SymbolData{
		FullyQualifiedNames: treeset.NewWithStringComparator(name),
		ExportedSymbols:     treeset.NewWithStringComparator(),
	}
}

func (s *SymbolData) Union(other *SymbolData) *SymbolData {
	return &SymbolData{
		FullyQualifiedNames: s.FullyQualifiedNames.Union(other.FullyQualifiedNames),
		ExportedSymbols:     s.ExportedSymbols.Union(other.ExportedSymbols),
	}
}

type ParseResult struct {
	File    string       `json:"source"`
	Imports *treeset.Set `json:"imports"`
	Package string       `json:"package"`
	*SymbolData
	// HasMain bool
}

func EmptyParseResult(file string) *ParseResult {
	return &ParseResult{
		File:       file,
		Imports:    treeset.NewWithStringComparator(),
		SymbolData: EmptySymbolData(),
	}
}

// TODO(jacob): For some reason we get a nil pointer deference from the treeset library
//
//	when trying to deserialize into cacheMap/ParseResult directly. For the time being
//	we brute force a workaround, but it would be great to either figure out why the
//	panic is happening and fix it, or have ParseResult implement UnmarshalJSON with a
//	Decoder to do its own stream parsing.
func (*treeSitterParser) UnmarshalParsingCache(
	cacheMap *map[string]*ParseResult,
	interfaceMap *map[string]interface{},
) {
	for hash, data := range *interfaceMap {
		parseResultMap := data.(map[string]interface{})

		file := parseResultMap["source"].(string)
		imports := parseResultMap["imports"].([]interface{})
		pkg := parseResultMap["package"].(string)
		fullyQualifiedNames := parseResultMap["fully_qualified_names"].([]interface{})
		exportedSymbols := parseResultMap["symbols"].([]interface{})

		(*cacheMap)[hash] = &ParseResult{
			File:    file,
			Imports: treeset.NewWithStringComparator(imports...),
			Package: pkg,
			SymbolData: &SymbolData{
				FullyQualifiedNames: treeset.NewWithStringComparator(fullyQualifiedNames...),
				ExportedSymbols:     treeset.NewWithStringComparator(exportedSymbols...),
			},
		}
	}
}

type Parser parse.CacheableParser[ParseResult]

type treeSitterParser struct {
	Parser
	parser                  *sitter.Parser
	debug                   bool
	verboseTreeSitterErrors bool
	dedupeParsing           bool
	seenNodes               *treeset.Set
}

var SCALA_LANG = scala.GetLanguage()

func scalaErrorQuery() *sitter.Query {
	query, err := sitter.NewQuery([]byte(`(ERROR) @error`), SCALA_LANG)

	if err != nil {
		fmt.Fprintf(os.Stderr, "Error querying for tree-sitter errors, exiting...\n")
		panic(err)
	}

	return query
}

var ERROR_QUERY = scalaErrorQuery()

func NewParser(debug bool, verboseTreeSitterErrors bool, dedupeParsing bool) Parser {
	sitter := sitter.NewParser()
	sitter.SetLanguage(SCALA_LANG)

	return &treeSitterParser{
		parser:                  sitter,
		debug:                   debug,
		verboseTreeSitterErrors: verboseTreeSitterErrors,
		dedupeParsing:           dedupeParsing,
		seenNodes:               treeset.NewWithIntComparator(),
	}
}

func (p *treeSitterParser) Parse(
	filePath string,
	source string,
) (*ParseResult, []error) {

	result := EmptyParseResult(filePath)
	errs := make([]error, 0)

	ctx := context.Background()
	sourceCode := []byte(source)

	tree, err := p.parser.ParseCtx(ctx, nil, sourceCode)
	if err != nil {
		errs = append(errs, err)
	}

	if tree != nil {
		rootNode := tree.RootNode()
		rootIsError := rootNode.Type() == "ERROR"

		if p.debug {
			fmt.Fprintf(os.Stderr, "%+v\n", rootNode)
		}

		for i := 0; i < int(rootNode.NamedChildCount()); i++ {
			nodeI := rootNode.NamedChild(i)

			switch nodeI.Type() {
			case "package_clause":
				packageChild := getLoneChild(nodeI, "package_identifier")
				parsedPackage := readPackageIdentifier(packageChild, sourceCode, false)

				if result.Package != "" {
					result.Package += "." + parsedPackage
				} else {
					result.Package = parsedPackage
				}

			case "import_declaration":
				importedSymbols := readImportDeclaration(nodeI, sourceCode)
				result.Imports = result.Imports.Union(importedSymbols)

			case "block":
				// For some reason tree-sitter sometimes puts blocks attached to class/object/etc
				// definitions as sibling nodes rather than nested as the body of their would-be
				// parent node. Just skip these as they are handled when parsing the definition
				// node.

			default:
				if !rootIsError {
					initialNamespace := ""
					childSymbolData := p.recursivelyParseSymbols(nodeI, sourceCode, &initialNamespace)
					result.SymbolData = result.SymbolData.Union(childSymbolData)
				}
			}
		}

		if rootIsError {
			result.ExportedSymbols = scanForDefinedSymbols(sourceCode)
		}

		if p.verboseTreeSitterErrors {
			if treeErrors := p.queryErrors(sourceCode, rootNode); treeErrors != nil {
				errs = append(errs, treeErrors...)
			}
		}
	}

	return result, errs
}

// Taken from https://github.com/aspect-build/aspect-cli/blob/v1.509.25/gazelle/common/treesitter/queries.go#L93.
// We unfortunately can't use their implementation as it refers to a hard-coded mapping
// of languages they support.
func (p *treeSitterParser) queryErrors(sourceCode []byte, node *sitter.Node) []error {
	if !node.HasError() {
		return nil
	}

	errors := make([]error, 0)

	// Execute the import query
	qc := sitter.NewQueryCursor()
	defer qc.Close()
	qc.Exec(ERROR_QUERY, node)

	// Collect import statements from the query results
	for {
		m, ok := qc.NextMatch()
		if !ok {
			break
		}

		for _, c := range m.Captures {
			at := c.Node
			atStart := at.StartPoint()
			show := c.Node

			// Navigate up the AST to include the full source line
			if atStart.Column > 0 {
				for show.StartPoint().Row > 0 && show.StartPoint().Row == atStart.Row {
					show = show.Parent()
				}
			}

			// Extract only that line from the parent Node
			lineI := int(atStart.Row - show.StartPoint().Row)
			colI := int(atStart.Column)
			line := strings.Split(show.Content(sourceCode), "\n")[lineI]

			pre := fmt.Sprintf("     %d: ", atStart.Row+1)
			msg := pre + line
			arw := strings.Repeat(" ", len(pre)+colI) + "^"

			errors = append(errors, fmt.Errorf(msg+"\n"+arw))
		}
	}

	return errors
}

/* NOTE(jacob): This regex is very much a simplification of the Scala grammar, and will
 *		miss symbols or get them wrong! Notably it misses many operator characters. See
 *		https://www.scala-lang.org/files/archive/spec/2.12/01-lexical-syntax.html#identifiers
 *		for the exact language specification.
 *
 *	 Additionally, to cut down on false positives it only looks for top-level symbols
 *		defined with no indentation at the start of their line of code.
 */
var DEFINITION_REGEX = regexp.MustCompile(
	`^(?:(?:abstract|final)\s+)?(?:class|object|trait|type|val|var)\s+(\w+)\s+.*`,
)

func scanForDefinedSymbols(sourceCode []byte) *treeset.Set {
	symbols := treeset.NewWithStringComparator()
	scanner := bufio.NewScanner(bytes.NewReader(sourceCode))
	scanner.Split(bufio.ScanLines)

	for scanner.Scan() {
		line := scanner.Text()
		if lineHasAccessModifier(line) {
			continue
		}

		definition := DEFINITION_REGEX.FindStringSubmatch(line)
		if definition != nil {
			symbols.Add(definition[1])
		}
	}

	return symbols
}

func (p *treeSitterParser) checkForDoubleParsing(node *sitter.Node, sourceCode []byte) {
	intID := int(node.ID())
	if p.seenNodes.Contains(intID) {
		fmt.Fprintf(
			os.Stderr,
			"Scanning node %d multiple times:\n%s\n",
			intID,
			node.Content(sourceCode),
		)
		os.Exit(1)
	} else {
		p.seenNodes.Add(intID)
	}
}

func (p *treeSitterParser) recursivelyParseSymbols(
	node *sitter.Node,
	sourceCode []byte,
	namespace *string,
) *SymbolData {
	if p.dedupeParsing {
		p.checkForDoubleParsing(node, sourceCode)
	}

	nodeType := node.Type()

	if isDefinition(nodeType) {
		return p.parseDefinition(node, sourceCode, namespace)

	} else if nodeType == "val_definition" || nodeType == "var_definition" {
		return p.parseVariableDefinition(node, sourceCode, namespace)

	} else if nodeType == "case_clause" ||
		nodeType == "catch_clause" ||
		isCodeBlock(nodeType) ||
		isImplementationExpression(nodeType) {
		return p.parseChildren(node, sourceCode, nil)

	} else if nodeType == "ERROR" {
		if p.debug {
			fmt.Fprintf(
				os.Stderr,
				"Scanned tree-sitter parse error while searching for symbols: %s\n",
				node.Content(sourceCode),
			)
		}
		// We might end up with some gibberish, but do our best to recover from
		// tree-sitter parse errors.
		return p.parseChildren(node, sourceCode, namespace)

	} else if nodeType == "field_expression" {
		if usedName, ok := readFieldExpression(node, sourceCode); ok {
			return SingleNameData(usedName)
		}

	} else if nodeType == "stable_type_identifier" {
		usedName := readStableTypeIdentifier(node, sourceCode)
		return SingleNameData(usedName)

	} else if nodeType == "import_declaration" {
		/* TODO(jacob): Handle inline imports. These are tricky as they can be relative to
		 *    symbols defined in the file itself, e.g.:
		 *
		 *      object HelloSpark {
		 *        val spark = SparkSession.builder.appName("Hello").getOrCreate()
		 *        import spark.implicits._
		 *        ...
		 *      }
		 *
		 *    In this example, there's no actual new dependencies we'd need to add, but if we
		 *    just blindly added `spark.implicits._` to our import set we might be unable to
		 *    map it to a providing package later on.
		 */
		return EmptySymbolData()

	} else if !isSkippable(nodeType) {
		fmt.Printf(
			"Symbol parsing found unexpected node type '%s' within: %s\n",
			nodeType,
			node.Content(sourceCode),
		)
		if p.debug {
			fmt.Fprintf(os.Stderr, "Relevant node structure: %+v\n", node)
		}
		return EmptySymbolData()
	}

	return EmptySymbolData()
}

/* TODO(jacob): This function does not correctly export object symbols defined in parent
 *    classes/traits. E.g. in the following code:
 *
 *        trait Hi {
 *          def hi(): Unit = println("hi")
 *        }
 *
 *        object Hello extends Hi
 *
 *    we should be exporting "Hello.hi" as a defined symbol, but this requires tracking
 *    some state around parent/child definitions which we don't currently do.
 */
func (p *treeSitterParser) parseDefinition(
	node *sitter.Node,
	sourceCode []byte,
	namespace *string,
) *SymbolData {
	nodeType := node.Type()
	symbolData := EmptySymbolData()

	maybeParse := func(field string) {
		fieldNode := node.ChildByFieldName(field)
		if fieldNode != nil {
			fieldSymbolData := p.recursivelyParseSymbols(fieldNode, sourceCode, nil)
			symbolData = symbolData.Union(fieldSymbolData)
		}
	}

	// Attempt to find the body for this class/object/function/etc definition if it exists.
	// This could be in several different places:
	//   1. A child named "body" on this node (ideal case!)
	//   2. The next sibling node to this one. Sometimes tree-sitter builds the parse tree
	//      in this way instead of nesting the body node as a child. I don't know why.
	//   3. In the case of parse errors, the body for this definition node might actually
	//      be the next sibling of our parent.
	body := node.ChildByFieldName("body")
	if body == nil {
		nextNode := node.NextSibling()
		if nextNode != nil && nextNode.Type() == "block" {
			body = nextNode
		} else if nextNode == nil {
			parentNode := node.Parent()
			if parentNode.Type() == "ERROR" {
				parentSibling := parentNode.NextSibling()
				if parentSibling != nil && parentSibling.Type() == "block" {
					body = parentSibling
				}
			}
		}
	}

	var newNamespace *string = nil
	if namespace != nil && !nodeHasAccessModifier(node) {
		// NOTE(jacob): For now, just assume any access modifier means this symbol
		//    is not exported. Note this is particularly untrue for private class
		//    constructors which use a `def this(...)` as their public interface.
		name := node.ChildByFieldName("name")
		symbol := *namespace + name.Content(sourceCode)
		symbolData.ExportedSymbols.Add(symbol)

		if nodeType == "object_definition" || nodeType == "package_object" {
			dottedSymbol := symbol + "."
			newNamespace = &dottedSymbol
		}
	}

	if body != nil {
		for i := 0; i < int(body.NamedChildCount()); i++ {
			// For some reason tree-sitter sometimes puts blocks attached to class/object/etc
			// definitions as sibling nodes rather than nested as the body of their would-be
			// parent node. Just skip these as they are handled when parsing the definition
			// node.
			if child := body.NamedChild(i); child.Type() != "block" {
				childSymbolData := p.recursivelyParseSymbols(child, sourceCode, newNamespace)
				symbolData = symbolData.Union(childSymbolData)
			}
		}
	}

	switch nodeType {
	case "class_definition", "trait_definition":
		maybeParse("class_parameters")
		maybeParse("derive")
		maybeParse("extend")
		maybeParse("type_parameters")

	case "extension_definition":
		maybeParse("parameters")
		maybeParse("type_parameters")

	case "function_definition":
		maybeParse("parameters")
		maybeParse("return_type")
		maybeParse("type_parameters")

	case "given_definition":
		maybeParse("arguments")
		maybeParse("parameters")
		maybeParse("return_type")
		maybeParse("type_parameters")

	case "object_definition", "package_object":
		maybeParse("derive")
		maybeParse("extend")

	case "type_definition":
		maybeParse("bound")
		maybeParse("type")
		maybeParse("type_parameters")
	}

	return symbolData
}

func (p *treeSitterParser) parseVariableDefinition(
	node *sitter.Node,
	sourceCode []byte,
	namespace *string,
) *SymbolData {
	symbolData := EmptySymbolData()

	// Assume anything marked private/protected/etc is not exported and skip it.
	if namespace != nil && !nodeHasAccessModifier(node) {
		pattern := node.ChildByFieldName("pattern")
		if pattern.Type() == "case_class_pattern" {
			// TODO(jacob): We could also be binding symbols via pattern case syntax, e.g.
			//    `val Array(one, two) = Array(1, 2)`. Just ignore this for now.
		} else {
			symbolData.ExportedSymbols.Add(*namespace + pattern.Content(sourceCode))
		}
	}

	valueNode := node.ChildByFieldName("value")
	valueSymbolData := p.recursivelyParseSymbols(valueNode, sourceCode, nil)
	return symbolData.Union(valueSymbolData)
}

func (p *treeSitterParser) parseChildren(
	node *sitter.Node,
	sourceCode []byte,
	namespace *string,
) *SymbolData {
	symbolData := EmptySymbolData()

	for i := 0; i < int(node.NamedChildCount()); i++ {
		childNode := node.NamedChild(i)
		childSymbolData := p.recursivelyParseSymbols(childNode, sourceCode, namespace)
		symbolData = symbolData.Union(childSymbolData)
	}

	return symbolData
}

func isCodeBlock(nodeType string) bool {
	switch nodeType {
	case "block",
		"case_block",
		"indented_block",
		"indented_cases",
		"macro_body",
		"template_body":
		return true

	default:
		return false
	}
}

func isDefinition(nodeType string) bool {
	switch nodeType {
	case "class_definition",
		"enum_definition",
		"extension_definition",
		"function_definition",
		"given_definition",
		"object_definition",
		"package_object",
		"trait_definition",
		"type_definition":
		return true

	default:
		return false
	}
}

func isImplementationExpression(nodeType string) bool {
	switch nodeType {
	case "alternative_pattern",
		"annotated_type",
		"annotation",
		"arguments",
		"ascription_expression",
		"assignment_expression",
		"binding",
		"bindings",
		"call_expression",
		"capture_pattern",
		"case_class_pattern",
		"class_parameter",
		"class_parameters",
		"colon_argument",
		"compound_type",
		"context_bound",
		"do_while_expression",
		"enum_body",
		"enumerator",
		"enumerators",
		"extends_clause",
		"finally_clause",
		"for_expression",
		"function_type",
		"generic_function",
		"generic_type",
		"guard",
		"if_expression",
		"infix_expression",
		"infix_pattern",
		"infix_type",
		"instance_expression",
		"interpolated_string",
		"interpolated_string_expression",
		"interpolation",
		"lambda_expression",
		"lazy_parameter_type",
		"lower_bound",
		"match_expression",
		"match_type",
		"parameter",
		"parameter_types",
		"parameters",
		"parenthesized_expression",
		"postfix_expression",
		"prefix_expression",
		"projected_type",
		"quote_expression",
		"refinement",
		"return_expression",
		"singleton_type",
		"structural_type",
		"throw_expression",
		"try_expression",
		"tuple_expression",
		"tuple_pattern",
		"tuple_type",
		"type_arguments",
		"type_case_clause",
		"type_parameters",
		"typed_pattern",
		"upper_bound",
		"view_bound",
		"while_expression":
		return true

	default:
		return false
	}
}

func isSkippable(nodeType string) bool {
	switch nodeType {
	// Comments are obviously skippable.
	case "block_comment",
		"comment",

		// Declarations in traits are ultimately picked up when defined by extension classes.
		"function_declaration",
		"val_declaration",
		"var_declaration",

		// Literals and other symbols types we don't really need to handle on their own.
		"boolean_literal",
		"character_literal",
		"contravariant_type_parameter",
		"covariant_type_parameter",
		"floating_point_literal",
		"identifier",
		"integer_literal",
		"literal_type",
		"modifiers",
		"null_literal",
		"operator_identifier",
		"repeat_pattern",
		"repeated_parameter_type",
		"self_type",
		"stable_identifier",
		"string",
		"type_identifier",
		"unit",
		"wildcard":
		return true

	default:
		return false
	}
}

func nodeHasAccessModifier(node *sitter.Node) bool {
	if modifiers := getLoneChild(node, "modifiers"); modifiers != nil {
		if access_modifier := getLoneChild(modifiers, "access_modifier"); access_modifier != nil {
			return true
		}
	}

	return false
}

var ACCESS_MODIFIER_REGEX = regexp.MustCompile(`\b(?:private|protected)\b`)

func lineHasAccessModifier(line string) bool {
	return ACCESS_MODIFIER_REGEX.MatchString(line)
}

func getLoneChild(node *sitter.Node, childType string) *sitter.Node {
	for i := 0; i < int(node.NamedChildCount()); i++ {
		if node.NamedChild(i).Type() == childType {
			return node.NamedChild(i)
		}
	}

	return nil
}

func readStableTypeIdentifier(node *sitter.Node, sourceCode []byte) string {
	nodeType := node.Type()
	if nodeType != "stable_type_identifier" {
		fmt.Fprintf(
			os.Stderr,
			"Must be type 'stable_type_identifier': %v - %s\n",
			nodeType,
			node.Content(sourceCode),
		)
		os.Exit(1)
	}

	return node.Content(sourceCode)
}

/* Returns a fully qualified name if one is found, along with a boolean indicating if
 * that is the case.
 *
 * Field expressions can contain any manner of child node types, but the ones we
 * care about are nested in reverse and look something like:
 *  (field_expression
 *      field: (identifier)
 *      value: (field_expression
 *          field: (identifier)
 *          value: (field_expression
 *              field: (identifier)
 *              value: (identifier)
 *          )
 *      )
 *  )
 * e.g. for `io.fsq.rogue.Rogue`:
 *  (field_expression
 *      field: (Rogue)
 *      value: (field_expression
 *          field: (rogue)
 *          value: (field_expression
 *              field: (fsq)
 *              value: (io)
 *          )
 *      )
 *  )
 */
func readFieldExpression(node *sitter.Node, sourceCode []byte) (string, bool) {
	nodeType := node.Type()
	if nodeType != "field_expression" {
		fmt.Fprintf(
			os.Stderr,
			"Must be type 'field_expression': %v - %s\n",
			nodeType,
			node.Content(sourceCode),
		)
		os.Exit(1)
	}
	fieldNode := node.ChildByFieldName("field")
	name := fieldNode.Content(sourceCode)
	child := node.ChildByFieldName("value")
	childType := child.Type()

	if childType == "field_expression" {
		namePrefix, ok := readFieldExpression(child, sourceCode)
		return namePrefix + "." + name, ok

	} else if childType == "identifier" {
		id := child.Content(sourceCode)
		if id == "" {
			// Implicits for DSLs such as scala xml or liftweb's inline html confuse
			// tree-sitter. Most of the time we just handle weird parses gracefully,
			// but here it can lead to missing identifiers.
			return "", false
		}
		if id != "_root_" {
			// We don't support relative imports currently, so everything is globally-
			// scoped and we want to just ignore the _root_ prefix.
			name = id + "." + name
		}
		return name, true

	} else {
		// TODO(jacob): There _technically_ might still be other field_expression nodes
		//      in children of this node.
		return "", false
	}
}

func readPackageIdentifier(node *sitter.Node, sourceCode []byte, ignoreLast bool) string {
	nodeType := node.Type()
	if nodeType != "package_identifier" {
		fmt.Fprintf(
			os.Stderr,
			"Must be type 'package_identifier': %v - %s\n",
			nodeType,
			node.Content(sourceCode),
		)
		os.Exit(1)
	}

	var s strings.Builder

	total := int(node.NamedChildCount())
	if ignoreLast {
		total = total - 1
	}

	for c := 0; c < total; c++ {
		nodeC := node.NamedChild(c)
		nodeCType := nodeC.Type()

		if nodeCType == "identifier" || nodeCType == "operator_identifier" {
			if s.Len() > 0 {
				s.WriteString(".")
			}
			s.WriteString(nodeC.Content(sourceCode))
		} else {
			fmt.Fprintf(
				os.Stderr,
				"Unexpected node type '%v' within: %s\n",
				nodeCType,
				node.Content(sourceCode),
			)
			os.Exit(1)
		}
	}

	return s.String()
}

func readNamespaceSelectors(node *sitter.Node, sourceCode []byte) *treeset.Set {
	nodeType := node.Type()
	if nodeType != "namespace_selectors" {
		fmt.Fprintf(
			os.Stderr,
			"Must be type 'package_identifier': %v - %s\n",
			nodeType,
			node.Content(sourceCode),
		)
		os.Exit(1)
	}

	imports := treeset.NewWithStringComparator()

	for c := 0; c < int(node.NamedChildCount()); c++ {
		nodeC := node.NamedChild(c)
		nodeCType := nodeC.Type()

		if nodeCType == "identifier" || nodeCType == "operator_identifier" {
			imports.Add(nodeC.Content(sourceCode))

		} else if nodeCType == "namespace_wildcard" {
			imports.Add("_")

		} else if nodeCType == "arrow_renamed_identifier" {
			imports.Add(nodeC.ChildByFieldName("name").Content(sourceCode))

		} else {
			fmt.Fprintf(
				os.Stderr,
				"Unexpected node type '%v' within: %s\n",
				nodeCType,
				node.Content(sourceCode),
			)
			os.Exit(1)
		}
	}

	return imports
}

/* imports look something like:
 *	(import_declaration
 * 		path: (identifier)
 * 		path: (identifier)
 * 		path: (identifier)
 *		(namespace_selectors
 * 			(identifier)
 * 			(arrow_renamed_identifier name: (identifier) alias: (identifier))
 * 		)
 * 	)
 * e.g. for `import com.twitter.util.{Await, TimeoutException => TUTimeoutException}`:
 *	(import_declaration
 * 		path: ("com")
 * 		path: ("twitter")
 * 		path: ("util")
 *		(namespace_selectors
 * 			("Await")
 * 			(arrow_renamed_identifier name: ("TimeoutException") alias: ("TUTimeoutException"))
 * 		)
 * 	)
 */
func readImportDeclaration(node *sitter.Node, sourceCode []byte) *treeset.Set {
	nodeType := node.Type()
	if nodeType != "import_declaration" {
		fmt.Fprintf(
			os.Stderr,
			"Must be type 'identifier': %v - %s\n",
			nodeType,
			node.Content(sourceCode),
		)
		os.Exit(1)
	}

	var importBuilder strings.Builder
	imports := treeset.NewWithStringComparator()

	for c := 0; c < int(node.NamedChildCount()); c++ {
		nodeC := node.NamedChild(c)
		nodeCType := nodeC.Type()

		if nodeCType == "identifier" || nodeCType == "operator_identifier" {
			if importBuilder.Len() > 0 {
				importBuilder.WriteString(".")
			}
			importBuilder.WriteString(nodeC.Content(sourceCode))

		} else if nodeCType == "namespace_selectors" {
			importBuilder.WriteString(".")
			importPackage := importBuilder.String()

			symbols := readNamespaceSelectors(nodeC, sourceCode)
			it := symbols.Iterator()
			for it.Next() {
				symbol := it.Value()
				imports.Add(importPackage + symbol.(string))
			}

			return imports

		} else if nodeCType == "namespace_wildcard" {
			importBuilder.WriteString("._")
			imports.Add(importBuilder.String())
			return imports

		} else if nodeCType != "comment" && nodeCType != "block_comment" {
			fmt.Fprintf(
				os.Stderr,
				"Unexpected node type '%v' within: %s\n",
				nodeCType,
				node.Content(sourceCode),
			)
			os.Exit(1)
		}
	}

	// Single symbol imports without wildcards or braces will fall through here.
	imports.Add(importBuilder.String())
	return imports
}
