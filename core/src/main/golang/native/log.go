package main

//#include "bridge.h"
import "C"

import (
	"strings"
	"time"
	"unsafe"

	"cfa/native/app"

	"github.com/metacubex/mihomo/log"
)

type message struct {
	Level   string `json:"level"`
	Message string `json:"message"`
	Time    int64  `json:"time"`
}

// shouldForwardToLogcat decides whether an engine log line earns an Android logcat write.
//
// Every forwarded line costs a CString malloc, a JNI hop, a write syscall into logd and a free.
// mihomo emits one INFO line per connection and logs DNS activity at DEBUG, so on a busy device
// this used to be thousands of lines a minute in release builds, for output nobody reads. It is
// not only CPU: the upstream log channel is unbuffered ahead of a 200-slot subscriber queue, so
// a slow consumer here backpressures into whichever tunnel goroutine emitted the line.
//
// Note the level check was missing entirely before — sibling subscribeLogcat has always applied
// one, so `Debugln` output was reaching logcat even at `log-level: info`.
//
// Debug builds keep the firehose (minus what the configured level already suppresses), which is
// what makes `adb logcat` worth reading while developing. Release builds forward warnings, errors
// and our own "[APP]" breadcrumbs. The in-app log screen is unaffected: it runs off
// subscribeLogcat, which does its own filtering.
func shouldForwardToLogcat(msg log.Event) bool {
	if strings.HasPrefix(msg.Payload, "[APP]") {
		return true
	}

	if msg.LogLevel < log.Level() {
		return false
	}

	return app.DebugBuild() || msg.LogLevel >= log.WARNING
}

func init() {
	go func() {
		sub := log.Subscribe()
		defer log.UnSubscribe(sub)

		for msg := range sub {
			if !shouldForwardToLogcat(msg) {
				continue
			}

			cPayload := C.CString(msg.Payload)

			switch msg.LogLevel {
			case log.INFO:
				C.log_info(cPayload)
			case log.ERROR:
				C.log_error(cPayload)
			case log.WARNING:
				C.log_warn(cPayload)
			case log.DEBUG:
				C.log_debug(cPayload)
			case log.SILENT:
				C.log_verbose(cPayload)
			}
		}
	}()
}

//export subscribeLogcat
func subscribeLogcat(remote unsafe.Pointer) {
	go func(remote unsafe.Pointer) {
		defer guard("subscribeLogcat")()

		sub := log.Subscribe()
		defer log.UnSubscribe(sub)

		for msg := range sub {
			if msg.LogLevel < log.Level() && !strings.HasPrefix(msg.Payload, "[APP]") {
				continue
			}

			rMsg := &message{
				Level:   msg.LogLevel.String(),
				Message: msg.Payload,
				Time:    time.Now().UnixNano() / 1000 / 1000,
			}

			if C.logcat_received(remote, marshalJson(rMsg)) != 0 {
				C.release_object(remote)

				log.Debugln("Logcat subscriber closed")

				break
			}
		}
	}(remote)

	log.Infoln("[APP] Logcat level: %s", log.Level().String())
}
