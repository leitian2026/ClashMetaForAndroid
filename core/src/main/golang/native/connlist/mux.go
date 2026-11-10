package connlist

// CUSTOM (CUSTOM.md #20, not part of upstream): for every node, how many sockets the core really has
// open to it and how many connections it carries, so the app can show whether multiplexing works.
// Nothing here runs on its own; it is called when the page asks.

import (
	"sort"

	"cfa/native/ipdelay"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

// SortMux is not an order: asked with it, queryCustomConnections answers with QueryMux instead.
const SortMux = 2

const (
	maxMuxNodes = 30
	maxMuxIPs   = 12
)

// MuxIP is the number of open sockets a node has to one address.
type MuxIP struct {
	IP      string `json:"ip"`
	Count   int    `json:"count"`
	Country string `json:"country,omitempty"` // only when already known, never looked up here
}

// MuxNode tells, for one node, how many sockets the core really has open to it (Tcp, and Udp) and
// how many connections (requests) are carried by the node. With multiplexing working, Tcp stays far
// below Requests. Node "" holds the sockets that belong to no node (DNS and the like).
type MuxNode struct {
	Node     string  `json:"node"`
	Tcp      int     `json:"tcp"`
	Udp      int     `json:"udp"`
	Requests int     `json:"requests"`
	IPs      []MuxIP `json:"ips,omitempty"`
	UdpIPs   []MuxIP `json:"udpIps,omitempty"` // the addresses of the UDP sockets; IP "" when the socket was made without a peer
}

// MuxSnapshot is the answer of QueryMux. Total counts every connection of the core.
type MuxSnapshot struct {
	Total int       `json:"total"`
	Mux   []MuxNode `json:"mux"`
}

// QueryMux joins the socket counts of the core (dialer.ConnCounts) with the number of connections
// each node carries (the first name of the route of a connection is the node that carries it). The
// order is fixed, so an unchanged state gives an unchanged answer.
func QueryMux() (result MuxSnapshot) {
	result.Mux = []MuxNode{}

	defer func() {
		if r := recover(); r != nil {
			log.Warnln("connections: %v", r)
		}
	}()

	requests := map[string]int{}

	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		result.Total++

		if chain := c.Chains(); len(chain) > 0 {
			requests[chain[0]]++
		}

		return true
	})

	result.Mux = buildMux(requests)

	return result
}

func buildMux(requests map[string]int) []MuxNode {
	byNode := map[string]*MuxNode{}

	get := func(name string) *MuxNode {
		n, ok := byNode[name]
		if !ok {
			n = &MuxNode{Node: name}
			byNode[name] = n
		}

		return n
	}

	for _, c := range dialer.ConnCounts() {
		n := get(c.Owner)

		if c.Network == "udp" {
			n.Udp += c.Count
			n.UdpIPs = append(n.UdpIPs, MuxIP{IP: c.IP, Count: c.Count, Country: countryOf(c.Owner, c.IP)})

			continue
		}

		n.Tcp += c.Count
		n.IPs = append(n.IPs, MuxIP{IP: c.IP, Count: c.Count, Country: countryOf(c.Owner, c.IP)})
	}

	for name, count := range requests {
		get(name).Requests = count
	}

	nodes := make([]MuxNode, 0, len(byNode))

	for _, n := range byNode {
		if n.Tcp == 0 && n.Udp == 0 && n.Requests == 0 {
			continue
		}

		sortIPs(n.IPs)
		sortIPs(n.UdpIPs)

		n.IPs = capIPs(n.IPs)
		n.UdpIPs = capIPs(n.UdpIPs)

		nodes = append(nodes, *n)
	}

	sort.Slice(nodes, func(i, j int) bool {
		a, b := nodes[i], nodes[j]

		if a.Tcp != b.Tcp {
			return a.Tcp > b.Tcp
		}

		if a.Udp != b.Udp {
			return a.Udp > b.Udp
		}

		if a.Requests != b.Requests {
			return a.Requests > b.Requests
		}

		return a.Node < b.Node
	})

	if len(nodes) > maxMuxNodes {
		nodes = nodes[:maxMuxNodes]
	}

	return nodes
}

// countryOf is the country of an address when the app already knows it; sockets without a peer have none.
func countryOf(node, ip string) string {
	if ip == "" {
		return ""
	}

	return ipdelay.CountryOf(node, ip)
}

func sortIPs(list []MuxIP) {
	sort.Slice(list, func(i, j int) bool {
		if list[i].Count != list[j].Count {
			return list[i].Count > list[j].Count
		}

		return list[i].IP < list[j].IP
	})
}

func capIPs(list []MuxIP) []MuxIP {
	if len(list) > maxMuxIPs {
		return list[:maxMuxIPs]
	}

	return list
}
