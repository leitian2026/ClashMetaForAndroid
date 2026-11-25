package main

import (
	"sync"

	"cfa/native/ipdelay"
	"cfa/native/tunnel"
)

// CUSTOM (CUSTOM.md #24): what the service process tells the core about the network.
//
//	"changed": record the network-change time and expire the reachability answer. A lit screen probes
//	           immediately; a dark screen defers the probe until the screen turns on.
//	"run":     latency test of every group, once. Only the core's own latency test, none of the extra
//	           per-IP requests of a manual test (CUSTOM.md #1). The call returns at once; the test runs on.
//
// Nothing here runs on its own: the service process calls it once after the network settled (and, when the
// screen was off at that moment, once when the screen turns on).
func retest(mode string) map[string]any {
	switch mode {
	case "changed":
		ipdelay.MarkNetworkChanged()
	case "run":
		runRetest()
	}

	return map[string]any{"ok": true}
}

func runRetest() {
	// no profile loaded yet (or the core is stopping): there is nothing to test
	defer func() { _ = recover() }()

	names := tunnel.QueryProxyGroupNames(false)
	if len(names) == 0 {
		return
	}

	var wg sync.WaitGroup

	for _, name := range names {
		wg.Add(1)

		go func(group string) {
			defer wg.Done()
			defer func() { _ = recover() }()

			tunnel.HealthCheck(group)
		}(name)
	}

	go func() {
		wg.Wait()
		ipdelay.MarkTested()
	}()
}
