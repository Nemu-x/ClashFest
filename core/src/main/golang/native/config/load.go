package config

import (
	"os"
	P "path"
	"runtime"
	"strings"

	"cfa/native/app"

	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/log"
)

func logDns(cfg *config.RawConfig) {
	bytes, err := yaml.Marshal(&cfg.DNS)
	if err != nil {
		log.Warnln("Marshal dns: %s", err.Error())

		return
	}

	log.Infoln("dns:")

	for _, line := range strings.Split(string(bytes), "\n") {
		log.Infoln("  %s", line)
	}
}

func UnmarshalAndPatch(profilePath string) (*config.RawConfig, error) {
	configPath := P.Join(profilePath, "config.yaml")

	configData, err := os.ReadFile(configPath)
	if err != nil {
		return nil, err
	}

	rawConfig, err := config.UnmarshalRawConfig(configData)
	if err != nil {
		return nil, err
	}

	if err := process(rawConfig, profilePath); err != nil {
		return nil, err
	}

	return rawConfig, nil
}

func Parse(rawConfig *config.RawConfig) (*config.Config, error) {
	cfg, err := config.ParseRawConfig(rawConfig)
	if err != nil {
		return nil, err
	}

	return cfg, nil
}

func Load(path string) error {
	rawCfg, err := UnmarshalAndPatch(path)
	if err != nil {
		log.Errorln("Load %s: %s", path, err.Error())

		return err
	}

	logDns(rawCfg)

	cfg, err := Parse(rawCfg)
	if err != nil {
		log.Errorln("Load %s: %s", path, err.Error())

		return err
	}

	// like hub.Parse()
	hub.ApplyConfig(cfg)

	app.ApplySubtitlePattern(rawCfg.ClashForAndroid.UiSubtitlePattern)

	runtime.GC()

	return nil
}

func Validate(path string) error {
	rawCfg, err := UnmarshalAndPatch(path)
	if err != nil {
		log.Errorln("Validate %s: %s", path, err.Error())

		return err
	}

	cfg, err := Parse(rawCfg)
	if err != nil {
		log.Errorln("Validate %s: %s", path, err.Error())

		return err
	}

	destroyProviders(cfg)

	return nil
}

func LoadDefault() {
	// The empty config has no groups to restore, and store-selected would make ApplyConfig open
	// the engine's cache file (an exclusive bbolt lock held for the life of the process) in every
	// process that loads the bridge: a UI process that got there first left the core in the
	// service process without fake-ip mappings. The real profile opens it in the service.
	rawCfg, err := config.UnmarshalRawConfig([]byte{})
	if err != nil {
		panic(err.Error())
	}
	rawCfg.Profile.StoreSelected = false
	cfg, err := config.ParseRawConfig(rawCfg)
	if err != nil {
		panic(err.Error())
	}

	hub.ApplyConfig(cfg)
}
