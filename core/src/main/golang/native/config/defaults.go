package config

import "cfa/native/config/fakeipdefaults"

var (
	defaultNameServers = []string{
		"223.5.5.5",
		"119.29.29.29",
		"8.8.4.4",
		"1.0.0.1",
	}
	defaultFakeIPFilter = fakeipdefaults.Filter()
	defaultFakeIPRange  = fakeipdefaults.Range
)
