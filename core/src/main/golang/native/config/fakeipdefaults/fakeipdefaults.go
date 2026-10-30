// Package fakeipdefaults holds the fake-ip settings patchDns gives a profile
// without a dns section, apart from the config package so that the desync
// profile generator can use them without the platform dependencies of config.
package fakeipdefaults

// Range is the default fake-ip-range.
const Range = "28.0.0.0/8"

// Filter returns the default fake-ip-filter.
func Filter() []string {
	return append([]string(nil), filter...)
}

var filter = []string{
	// Stun Services
	"+.stun.*.*",
	"+.stun.*.*.*",
	"+.stun.*.*.*.*",
	"+.stun.*.*.*.*.*",

	// Google Voices
	"lens.l.google.com",

	// Nintendo Switch STUN
	"*.n.n.srv.nintendo.net",

	// PlayStation STUN
	"+.stun.playstation.net",

	// XBox
	"xbox.*.*.microsoft.com",
	"*.*.xboxlive.com",

	// Microsoft Captive Portal
	"*.msftncsi.com",
	"*.msftconnecttest.com",

	// Bilibili CDN
	"*.mcdn.bilivideo.cn",

	// Windows Default LAN WorkGroup
	"WORKGROUP",
}
