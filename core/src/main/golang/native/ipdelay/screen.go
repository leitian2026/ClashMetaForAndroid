package ipdelay

import (
	"sync"

	"cfa/native/screenstate"
)

type pendingScreenWork struct {
	reach  bool
	notify bool
}

func (p *pendingScreenWork) markReach() {
	p.reach = true
}

func (p *pendingScreenWork) markNotify() {
	p.notify = true
}

func (p *pendingScreenWork) take(interactive bool) (bool, bool) {
	if !interactive {
		return false, false
	}

	reach, notify := p.reach, p.notify
	p.reach, p.notify = false, false
	return reach, notify
}

var (
	screenWorkMu sync.Mutex
	screenWork   pendingScreenWork
)

func init() {
	screenstate.RegisterListener(screenChanged)
}

func screenChanged(interactive bool) {
	if interactive {
		processPendingScreenWork()
	}
}

// deferReachIfScreenOff records one pending reach probe when the screen is off.
func deferReachIfScreenOff() bool {
	screenWorkMu.Lock()
	defer screenWorkMu.Unlock()

	if screenstate.Interactive() {
		return false
	}

	screenWork.markReach()
	return true
}

// deferNotifyIfScreenOff records one pending UI notification when the screen is off.
func deferNotifyIfScreenOff() bool {
	screenWorkMu.Lock()
	defer screenWorkMu.Unlock()

	if screenstate.Interactive() {
		return false
	}

	screenWork.markNotify()
	return true
}

func processPendingScreenWork() {
	screenWorkMu.Lock()
	reach, notify := screenWork.take(screenstate.Interactive())
	screenWorkMu.Unlock()

	if reach && !resumePendingReach() {
		screenWorkMu.Lock()
		screenWork.markReach()
		screenWorkMu.Unlock()
	}

	if notify {
		notifyNow()
	}
}
