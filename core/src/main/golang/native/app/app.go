package app

import (
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"cfa/native/useragent"
)

var appVersionName string
var coreVersion string
var platformVersion int
var installedAppsUid = map[int]string{}

// debugBuild gates verbosity that is only worth its cost while developing. Atomic because the
// core's log-forwarding goroutine starts when the library loads, before coreInit sets this.
var debugBuild atomic.Bool

func ApplyDebugBuild(debug bool) {
	debugBuild.Store(debug)
}

func DebugBuild() bool {
	return debugBuild.Load()
}

func ApplyVersionName(versionName string) {
	appVersionName = versionName
}

func ApplyPlatformVersion(version int) {
	platformVersion = version
}

func VersionName() string {
	return appVersionName
}

// ApplyCoreTag records the mihomo release tag the core was built from. Anything
// that is not a release tag leaves the core version unknown and the User-Agent
// then carries a bare `mihomo` token (see useragent).
func ApplyCoreTag(tag string) {
	coreVersion = useragent.CoreVersionFromTag(tag)
}

// CoreVersion is the bare mihomo release version ("1.19.32"), or "" when unknown.
func CoreVersion() string {
	return coreVersion
}

// SubscriptionUserAgent is the default User-Agent for subscription fetches; a
// per-profile override replaces it via MergeSubscriptionFetchHeaders.
func SubscriptionUserAgent() string {
	return useragent.Build(appVersionName, coreVersion)
}

func PlatformVersion() int {
	return platformVersion
}

func NotifyInstallAppsChanged(uidList string) {
	uids := map[int]string{}

	for _, item := range strings.Split(uidList, ",") {
		kv := strings.Split(item, ":")
		if len(kv) == 2 {
			uid, err := strconv.Atoi(kv[0])
			if err != nil {
				continue
			}

			uids[uid] = kv[1]
		}
	}

	installedAppsUid = uids
}

func QueryAppByUid(uid int) string {
	return installedAppsUid[uid]
}

func NotifyTimeZoneChanged(name string, offset int) {
	time.Local = time.FixedZone(name, offset)
}