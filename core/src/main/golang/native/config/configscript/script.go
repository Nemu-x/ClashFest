package configscript

import (
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/dop251/goja"
	"gopkg.in/yaml.v3"
)

// User config scripts, the shape the ecosystem already settled on: the script defines
//
//	function main(config) { ...; return config }
//
// and receives the whole config as a plain JS object. Other Clash clients implement the same
// contract on their own JS engines, some passing a second `profileName` argument, which we
// mirror — an extra argument is harmless to a script that declares only one parameter.
// Keeping the signature identical is the point: people copy scripts between clients.
//
// We run it here, in the engine, rather than in Kotlin, so the YAML is parsed and
// re-serialised by the same library the core loads configs with. A Kotlin-side round trip
// through snakeyaml would re-introduce the dialect drift we already carry
// EngineAlignedResolver for.
//
// The bridge into JS goes through JSON on purpose. goja can wrap a Go map directly, but
// then scripts mutate a Go-backed object with Go-typed values and the semantics get subtle
// (nested maps, integer widths, deletes). Parsing a JSON document inside the VM gives the
// script ordinary JS objects and nothing else, which is exactly what a script written for
// another client expects.
const (
	// A config transform is pure object shuffling; anything slower is a runaway loop.
	scriptTimeout = 3 * time.Second
	// Guards against a script that returns something enormous.
	maxScriptOutputBytes = 8 << 20
)

// Distinct causes, so the Kotlin side can map them to user-facing error codes instead of
// showing a raw engine string.
var (
	ErrScriptCompile  = errors.New("script-compile")
	ErrScriptNoMain   = errors.New("script-no-main")
	ErrScriptRuntime  = errors.New("script-runtime")
	ErrScriptTimeout  = errors.New("script-timeout")
	ErrScriptBadValue = errors.New("script-bad-value")
	ErrScriptTooLarge = errors.New("script-too-large")
)

// ApplyScript runs a user script over a config document and returns the rewritten YAML.
// The input is returned untouched when script is blank, so an enabled-but-empty script is
// a no-op rather than an error.
func ApplyScript(configYAML []byte, script string, profileName string) ([]byte, error) {
	if len(script) == 0 {
		return configYAML, nil
	}

	var doc any
	if err := yaml.Unmarshal(configYAML, &doc); err != nil {
		return nil, fmt.Errorf("parsing config: %w", err)
	}
	// yaml.v3 hands back map[string]any for string-keyed mappings, but nested documents can
	// still carry map[any]any (non-string keys). JSON cannot express those, so normalise.
	docJSON, err := json.Marshal(normalizeForJSON(doc))
	if err != nil {
		return nil, fmt.Errorf("encoding config: %w", err)
	}

	vm := goja.New()
	// No host bindings are installed: goja ships no fs, no net, no require, no timers. A
	// script can only touch the object it is handed. Do not add any.

	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-time.After(scriptTimeout):
			vm.Interrupt(ErrScriptTimeout)
		case <-done:
		}
	}()

	if _, err := vm.RunString(script); err != nil {
		return nil, classifyVMError(err, ErrScriptCompile)
	}

	mainFn, ok := goja.AssertFunction(vm.Get("main"))
	if !ok {
		return nil, fmt.Errorf("%w: the script must define `function main(config)`", ErrScriptNoMain)
	}

	// Hand the document over as a *string* to the VM's own JSON.parse rather than splicing
	// it into evaluated source. Embedding would mean escaping the characters where JSON is
	// not a subset of JS (U+2028/U+2029 terminate a line in JS source but are legal inside
	// JSON strings); passing a value sidesteps that class of bug entirely.
	jsonObj := vm.Get("JSON").ToObject(vm)
	jsonParse, ok := goja.AssertFunction(jsonObj.Get("parse"))
	if !ok {
		return nil, errors.New("script runtime is missing JSON.parse")
	}
	parsed, err := jsonParse(jsonObj, vm.ToValue(string(docJSON)))
	if err != nil {
		return nil, fmt.Errorf("loading config into the script: %w", err)
	}

	result, err := mainFn(goja.Undefined(), parsed, vm.ToValue(profileName))
	if err != nil {
		return nil, classifyVMError(err, ErrScriptRuntime)
	}
	if result == nil || goja.IsUndefined(result) || goja.IsNull(result) {
		return nil, fmt.Errorf("%w: main() returned nothing — it must `return config`", ErrScriptBadValue)
	}
	exported := result.Export()
	if _, isMap := exported.(map[string]any); !isMap {
		return nil, fmt.Errorf("%w: main() must return an object, got %T", ErrScriptBadValue, exported)
	}

	outJSON, err := json.Marshal(exported)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrScriptBadValue, err)
	}
	if len(outJSON) > maxScriptOutputBytes {
		return nil, fmt.Errorf("%w: %d bytes", ErrScriptTooLarge, len(outJSON))
	}

	var out any
	if err := json.Unmarshal(outJSON, &out); err != nil {
		return nil, fmt.Errorf("%w: %v", ErrScriptBadValue, err)
	}
	rendered, err := yaml.Marshal(out)
	if err != nil {
		return nil, fmt.Errorf("rendering config: %w", err)
	}
	return rendered, nil
}

// Result is the JNI-facing shape. The bridge is a single string in / single string out, so
// success and failure share one channel and the caller switches on Ok. Code is one of the
// sentinel strings above, which the Kotlin side maps to a user-facing error code — it never
// shows Message, which is raw engine output meant for the log.
type Result struct {
	Ok      bool   `json:"ok"`
	YAML    string `json:"yaml,omitempty"`
	Code    string `json:"code,omitempty"`
	Message string `json:"message,omitempty"`
}

// ApplyScriptJSON wraps [ApplyScript] for the native bridge.
func ApplyScriptJSON(configYAML []byte, script string, profileName string) string {
	out, err := ApplyScript(configYAML, script, profileName)
	var res Result
	if err != nil {
		res = Result{Ok: false, Code: codeOf(err), Message: err.Error()}
	} else {
		res = Result{Ok: true, YAML: string(out)}
	}
	encoded, marshalErr := json.Marshal(res)
	if marshalErr != nil {
		// Cannot happen for this struct, but never hand back a broken document.
		return `{"ok":false,"code":"script-runtime","message":"result encoding failed"}`
	}
	return string(encoded)
}

func codeOf(err error) string {
	for _, sentinel := range []error{
		ErrScriptTimeout, ErrScriptCompile, ErrScriptNoMain,
		ErrScriptRuntime, ErrScriptBadValue, ErrScriptTooLarge,
	} {
		if errors.Is(err, sentinel) {
			return sentinel.Error()
		}
	}
	return "script-runtime"
}

// classifyVMError turns an interrupt into ErrScriptTimeout and anything else into fallback.
func classifyVMError(err error, fallback error) error {
	var interrupted *goja.InterruptedError
	if errors.As(err, &interrupted) {
		return fmt.Errorf("%w: script exceeded %s", ErrScriptTimeout, scriptTimeout)
	}
	return fmt.Errorf("%w: %v", fallback, err)
}

// normalizeForJSON rewrites the map[any]any and non-string keys yaml.v3 can produce into
// something json.Marshal accepts, and flattens the few scalar types JSON has no notion of.
func normalizeForJSON(v any) any {
	switch t := v.(type) {
	case map[string]any:
		out := make(map[string]any, len(t))
		for k, val := range t {
			out[k] = normalizeForJSON(val)
		}
		return out
	case map[any]any:
		out := make(map[string]any, len(t))
		for k, val := range t {
			out[fmt.Sprint(k)] = normalizeForJSON(val)
		}
		return out
	case []any:
		out := make([]any, len(t))
		for i, val := range t {
			out[i] = normalizeForJSON(val)
		}
		return out
	case time.Time:
		return t.Format(time.RFC3339)
	default:
		return v
	}
}
