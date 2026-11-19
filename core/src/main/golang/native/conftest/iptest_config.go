package conftest

// CUSTOM (CUSTOM.md #69 and #71, not upstream): mode "config" of the test-IP page: the addresses of the profile's
// nodes, so they can be put into the list of "test other IPs".
//
// Since #71 this is the same list as the proxy page's node cards (ipdelay.PrepareDisplay + ipdelay.GetForDisplay,
// CUSTOM.md #55), not a second implementation of it: IPv4 only, at most maxHostsIPs per node, the ones in use first;
// the one address of a server that is an IP, the ones written in `hosts:` (exact key), or the ones found by resolving
// the domain once (cached, at most displayResolveTimeout). Nodes with `ip-version: ipv6` and nodes of providers are
// not in it, as on the proxy page.
//
// Nothing here depends on the core's source: it only calls the two functions above.
//
// Power: nothing runs on its own. One tap = the proxy page's own work once (a domain already resolved is not asked
// again).

import (
	"net/netip"
	"sort"
	"strings"

	"cfa/native/ipdelay"
)

// ConfigIP is one address read from the profile.
type ConfigIP struct {
	IP    string   `json:"ip"`
	From  []string `json:"from"`  // the nodes that use it (sorted)
	Via   string   `json:"via"`   // "server" (written as the node's server), "hosts" (in a hosts entry), "dns" (resolved)
	Name  string   `json:"name"`  // the hosts key or domain it came from, "" for "server"
	Chain bool     `json:"chain"` // every node that uses it has a `dialer-proxy`: a direct connection to it may not be possible
	InUse bool     `json:"inUse"` // requests went through it lately (the proxy page marks it too)
}

// ConfigFail is a server that gave no address.
type ConfigFail struct {
	Name   string   `json:"name"`   // the domain
	Nodes  []string `json:"nodes"`  // the nodes whose server it is (sorted)
	Reason string   `json:"reason"` // "failed"
	Detail string   `json:"detail"` // what is known about why, may be ""
}

// ConfigRead is the result of reading the profile.
type ConfigRead struct {
	IPs     []ConfigIP
	Fails   []ConfigFail
}

// displayIP is one address the proxy page shows for a node.
type displayIP struct {
	ip    string
	inUse bool
}

// displayNode is one node as the proxy page sees it.
type displayNode struct {
	name  string
	key   string // the hosts key or the domain; "" for a server that is an address
	via   string // "server", "hosts" or "dns"
	chain bool
	ips   []displayIP
}

// readConfigIPs reads the profile in memory and gives the addresses the proxy page shows for each node.
func readConfigIPs() ConfigRead {
	proxies := ipdelay.RawProxies()
	hosts := ipdelay.RawHosts()

	names := make([]string, 0, len(proxies))

	for name, raw := range proxies {
		if server, _ := raw["server"].(string); server != "" {
			names = append(names, name)
		}
	}

	sort.Strings(names)

	ipdelay.PrepareDisplay(names) // resolves, once, the domains that have no `hosts:` entry (waits at most 3 s)

	nodes := make([]displayNode, 0, len(names))

	for _, name := range names {
		raw := proxies[name]
		server, _ := raw["server"].(string)

		if v, _ := raw["ip-version"].(string); v == "ipv6" {
			continue // not on the proxy page
		}

		n := displayNode{name: name}

		if v, _ := raw["dialer-proxy"].(string); strings.TrimSpace(v) != "" {
			n.chain = true
		}

		if _, err := netip.ParseAddr(server); err == nil {
			n.via = "server"
		} else if _, found := hosts[server]; found {
			n.via, n.key = "hosts", server
		} else {
			n.via, n.key = "dns", server
		}

		for _, d := range ipdelay.GetForDisplay(name) {
			n.ips = append(n.ips, displayIP{ip: d.IP, inUse: d.InUse})
		}

		nodes = append(nodes, n)
	}

	return mergeDisplay(nodes)
}

// mergeDisplay puts the nodes' addresses together: one row per address (the nodes that use it listed, the ones in
// use first), one failure per domain. Pure, so a test can give it a table.
func mergeDisplay(nodes []displayNode) ConfigRead {
	out := ConfigRead{IPs: []ConfigIP{}, Fails: []ConfigFail{}}

	index := map[string]int{}
	allChain := map[string]bool{}
	failIndex := map[string]int{}

	for _, n := range nodes {
		if len(n.ips) == 0 {
			if n.via == "server" {
				continue // an address that is not IPv4: the proxy page does not show it either
			}

			if i, ok := failIndex[n.key]; ok {
				out.Fails[i].Nodes = append(out.Fails[i].Nodes, n.name)

				continue
			}

			detail := ""
			if n.via == "hosts" {
				detail = "hosts 里没有 IPv4"
			}

			failIndex[n.key] = len(out.Fails)
			out.Fails = append(out.Fails, ConfigFail{Name: n.key, Nodes: []string{n.name}, Reason: "failed", Detail: detail})

			continue
		}

		for _, d := range n.ips {
			i, ok := index[d.ip]
			if !ok {
				i = len(out.IPs)
				index[d.ip] = i
				allChain[d.ip] = true
				out.IPs = append(out.IPs, ConfigIP{IP: d.ip, From: []string{}, Via: n.via, Name: n.key})
			}

			if !containsStr(out.IPs[i].From, n.name) {
				out.IPs[i].From = append(out.IPs[i].From, n.name)
			}

			if d.inUse {
				out.IPs[i].InUse = true
			}

			if !n.chain {
				allChain[d.ip] = false
			}
		}
	}

	for i := range out.IPs {
		sort.Strings(out.IPs[i].From)

		out.IPs[i].Chain = allChain[out.IPs[i].IP]
	}

	sort.SliceStable(out.IPs, func(i, j int) bool { return out.IPs[i].InUse && !out.IPs[j].InUse })

	sort.SliceStable(out.Fails, func(i, j int) bool { return out.Fails[i].Name < out.Fails[j].Name })

	for i := range out.Fails {
		sort.Strings(out.Fails[i].Nodes)
	}

	return out
}

func containsStr(list []string, s string) bool {
	for _, e := range list {
		if e == s {
			return true
		}
	}

	return false
}
