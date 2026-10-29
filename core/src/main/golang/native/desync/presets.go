package desync

import (
	"errors"
	"fmt"
	"strings"

	lib "github.com/Nemu-x/desync"
	"github.com/Nemu-x/desync/presets"
)

// Options are the keys of a `type: desync` proxy (adapter/outbound/desync.go,
// DesyncOption) in the same names, units (milliseconds) and forms
// (comma-separated positions), so one value serves the JSON for the UI and
// the YAML of a generated profile.
type Options struct {
	TLSRec           string         `json:"tlsrec,omitempty" yaml:"tlsrec,omitempty"`
	Split            string         `json:"split,omitempty" yaml:"split,omitempty"`
	SplitDelay       int64          `json:"split-delay,omitempty" yaml:"split-delay,omitempty"`
	SplitWait        string         `json:"split-wait,omitempty" yaml:"split-wait,omitempty"`
	SplitWaitTimeout int64          `json:"split-wait-timeout,omitempty" yaml:"split-wait-timeout,omitempty"`
	Disorder         bool           `json:"disorder,omitempty" yaml:"disorder,omitempty"`
	OOB              bool           `json:"oob,omitempty" yaml:"oob,omitempty"`
	OOBByte          string         `json:"oob-byte,omitempty" yaml:"oob-byte,omitempty"`
	Random           *RandomOptions `json:"random,omitempty" yaml:"random,omitempty"`
	Fake             *FakeOptions   `json:"fake,omitempty" yaml:"fake,omitempty"`
	UDP              *UDPOptions    `json:"udp,omitempty" yaml:"udp,omitempty"`
	RejectQUIC       bool           `json:"reject-quic,omitempty" yaml:"reject-quic,omitempty"`
}

type RandomOptions struct {
	TLSRec []RandomCut `json:"tlsrec,omitempty" yaml:"tlsrec,omitempty"`
	Split  []RandomCut `json:"split,omitempty" yaml:"split,omitempty"`
	Seed   uint64      `json:"seed,omitempty" yaml:"seed,omitempty"`
}

type RandomCut struct {
	From     string `json:"from" yaml:"from"`
	To       string `json:"to" yaml:"to"`
	Count    int    `json:"count,omitempty" yaml:"count,omitempty"`
	MinPiece int    `json:"min-piece,omitempty" yaml:"min-piece,omitempty"`
}

type FakeOptions struct {
	TTL          int             `json:"ttl,omitempty" yaml:"ttl,omitempty"`
	AutoTTL      *AutoTTLOptions `json:"auto-ttl,omitempty" yaml:"auto-ttl,omitempty"`
	Prefix       int             `json:"prefix,omitempty" yaml:"prefix,omitempty"`
	Timeout      int64           `json:"timeout,omitempty" yaml:"timeout,omitempty"`
	SNI          []string        `json:"sni,omitempty" yaml:"sni,omitempty"`
	Randomize    bool            `json:"randomize,omitempty" yaml:"randomize,omitempty"`
	FromOriginal bool            `json:"from-original,omitempty" yaml:"from-original,omitempty"`
	Offset       int             `json:"offset,omitempty" yaml:"offset,omitempty"`
}

type AutoTTLOptions struct {
	Delta int `json:"delta,omitempty" yaml:"delta,omitempty"`
	Min   int `json:"min,omitempty" yaml:"min,omitempty"`
	Max   int `json:"max,omitempty" yaml:"max,omitempty"`
}

type UDPOptions struct {
	TTL      int    `json:"ttl" yaml:"ttl"`
	Repeats  int    `json:"repeats,omitempty" yaml:"repeats,omitempty"`
	Packets  int    `json:"packets,omitempty" yaml:"packets,omitempty"`
	Template string `json:"template,omitempty" yaml:"template,omitempty"`
	SNI      string `json:"sni,omitempty" yaml:"sni,omitempty"`
	QUICOnly bool   `json:"quic-only,omitempty" yaml:"quic-only,omitempty"`
}

func joinPositions(ps []lib.Pos) string {
	parts := make([]string, len(ps))
	for i, p := range ps {
		parts[i] = p.String()
	}
	return strings.Join(parts, ",")
}

func randomCuts(cuts []lib.RandomCut) []RandomCut {
	var out []RandomCut
	for _, c := range cuts {
		out = append(out, RandomCut{From: c.From.String(), To: c.To.String(), Count: c.Count, MinPiece: c.MinPiece})
	}
	return out
}

// tcpOptions converts library options into the outbound's keys. It fails for
// what the outbound cannot express (a custom payload, ClientHello limits), so
// such a preset is skipped instead of silently changing meaning.
func tcpOptions(o lib.Options) (Options, error) {
	if o.ClientHello != nil {
		return Options{}, errors.New("ClientHello limits have no desync outbound key")
	}
	out := Options{
		TLSRec:     joinPositions(o.TLSRec),
		Split:      joinPositions(o.Split),
		SplitDelay: o.SplitDelay.Milliseconds(),
		Disorder:   o.Disorder,
	}
	if o.SplitWait != lib.SplitWaitNone {
		out.SplitWait = o.SplitWait.String()
	}
	out.SplitWaitTimeout = o.SplitWaitTimeout.Milliseconds()
	if o.OOB != nil {
		out.OOB = true
		if o.OOB.Byte != 0 {
			out.OOBByte = string(rune(o.OOB.Byte))
		}
	}
	if r := o.Random; r != nil {
		out.Random = &RandomOptions{TLSRec: randomCuts(r.TLSRec), Split: randomCuts(r.Split), Seed: r.Seed}
	}
	if f := o.Fake; f != nil {
		if len(f.Payload) > 0 {
			return Options{}, errors.New("a custom fake payload has no desync outbound key")
		}
		out.Fake = &FakeOptions{
			TTL:          f.TTL,
			Prefix:       f.Prefix,
			Timeout:      f.Timeout.Milliseconds(),
			SNI:          append([]string(nil), f.SNI...),
			Randomize:    f.Randomize,
			FromOriginal: f.FromOriginal,
			Offset:       f.Offset,
		}
		if a := f.AutoTTL; a != nil {
			out.Fake.AutoTTL = &AutoTTLOptions{Delta: a.Delta, Min: a.Min, Max: a.Max}
		}
	}
	return out, nil
}

func udpOptions(o lib.UDPOptions) (*UDPOptions, error) {
	if len(o.Payload) > 0 {
		return nil, errors.New("a custom UDP payload has no desync outbound key")
	}
	out := &UDPOptions{TTL: o.TTL, Repeats: o.Repeats, Packets: o.Packets, SNI: o.SNI, QUICOnly: o.QUICOnly}
	switch o.Template {
	case lib.UDPTemplateQUICInitial:
		out.Template = "quic"
	default:
		out.Template = ""
	}
	return out, nil
}

// Preset is one catalog entry; Options holds only the keys it sets.
type Preset struct {
	ID          string  `json:"id"`
	Name        string  `json:"name"`
	Description string  `json:"description"`
	Rank        int     `json:"rank"`
	Options     Options `json:"options"`
}

// SkippedPreset is a catalog entry this device cannot use, with the reason.
type SkippedPreset struct {
	Kind   string `json:"kind"` // "tcp" or "udp"
	Name   string `json:"name"`
	Reason string `json:"reason"`
}

// Catalog is the strategy catalog filtered by the capabilities it was built
// for, each list in the library's suggested order of trying (rank).
type Catalog struct {
	CatalogVersion string `json:"catalogVersion"`
	// DefaultTCP is the ID GenerateConfig uses without a strategyPresetId:
	// the first TCP preset.
	DefaultTCP string          `json:"defaultTcp"`
	TCP        []Preset        `json:"tcp"`
	UDP        []Preset        `json:"udp"`
	Skipped    []SkippedPreset `json:"skipped"`
}

// catalogFor builds the catalog for caps. The baseline (no transformation) is
// left out: a desync outbound without one is a configuration error.
func catalogFor(caps lib.Capabilities) Catalog {
	c := Catalog{TCP: []Preset{}, UDP: []Preset{}, Skipped: []SkippedPreset{}}
	tcp, skipped := presets.Available(caps)
	for _, s := range skipped {
		c.Skipped = append(c.Skipped, SkippedPreset{Kind: "tcp", Name: s.Name, Reason: s.Reason})
	}
	var ids []string
	for _, p := range presets.Ranked(tcp) {
		if !p.Options.Enabled() {
			continue
		}
		opts, err := tcpOptions(p.Options)
		if err != nil {
			c.Skipped = append(c.Skipped, SkippedPreset{Kind: "tcp", Name: p.Name, Reason: err.Error()})
			continue
		}
		c.TCP = append(c.TCP, Preset{ID: p.ID(), Name: p.Name, Description: p.Description, Rank: p.Rank, Options: opts})
		ids = append(ids, p.ID())
	}
	udp, skipped := presets.AvailableUDP(caps)
	for _, s := range skipped {
		c.Skipped = append(c.Skipped, SkippedPreset{Kind: "udp", Name: s.Name, Reason: s.Reason})
	}
	for _, p := range presets.RankedUDP(udp) {
		opts, err := udpOptions(p.Options)
		if err != nil {
			c.Skipped = append(c.Skipped, SkippedPreset{Kind: "udp", Name: p.Name, Reason: err.Error()})
			continue
		}
		c.UDP = append(c.UDP, Preset{ID: p.ID(), Name: p.Name, Description: p.Description, Rank: p.Rank, Options: Options{UDP: opts}})
		ids = append(ids, p.ID())
	}
	c.CatalogVersion = presets.CatalogVersion(ids)
	if len(c.TCP) > 0 {
		c.DefaultTCP = c.TCP[0].ID
	}
	return c
}

// Presets is the catalog for this device.
func Presets() Catalog {
	return catalogFor(lib.PlatformCapabilities())
}

func (c Catalog) find(list []Preset, kind, id string) (Preset, error) {
	for _, p := range list {
		if p.ID == id {
			return p, nil
		}
	}
	for _, s := range c.Skipped {
		if m, ok := presets.ByID(id); ok && s.Kind == kind &&
			(m.TCP != nil && m.TCP.Name == s.Name || m.UDP != nil && m.UDP.Name == s.Name) {
			return Preset{}, fmt.Errorf("%s preset %s (%s) is not available here: %s", kind, id, s.Name, s.Reason)
		}
	}
	return Preset{}, fmt.Errorf("unknown %s preset %q", kind, id)
}
