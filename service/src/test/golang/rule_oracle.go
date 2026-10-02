// Invoked by RuleRoutingEditorTest; kept outside production native build inputs.
package main

import (
	"cfa/native/snapshot"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
)

func read(name string) []byte {
	data, err := os.ReadFile(filepath.Join(os.Args[1], name))
	if err != nil {
		panic(err)
	}
	return data
}

func main() {
	expected, actual := read("expected.yaml"), read("actual.yaml")
	for _, data := range [][]byte{expected, actual} {
		if verdict := snapshot.ValidateBytes(data); verdict != "" {
			panic(verdict)
		}
	}
	a, err := snapshot.ParseBytes(expected)
	if err != nil {
		panic(err)
	}
	b, err := snapshot.ParseBytes(actual)
	if err != nil {
		panic(err)
	}
	if !reflect.DeepEqual(a, b) {
		panic("rule serialization changed engine snapshot")
	}
	if verdict := snapshot.ValidateBytes(read("invalid.yaml")); verdict == "" {
		panic("unknown logical target was accepted")
	}
	fmt.Println("routing oracle accepted roundtrip and rejected invalid target")
}
