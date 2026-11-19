package main

// The NAT box of the lab. It stands between the client network (where the emulator, or a phone
// through the host, comes from) and the internal network of the PBX, and behaves towards the
// client like the NAT of a home router or a carrier would: the PBX sees every client as the
// box's own address. The tests change how it behaves with one-line commands over TCP:
//
//	mode cone|symmetric   cone keeps a client's port for every destination; symmetric takes a
//	                      new random one for each (masquerade fully-random)
//	udp-sip on|off        off: UDP to port 5060 is dropped (TCP still gets through)
//	max-udp N             UDP packets longer than N bytes (IP header included) are dropped; 0: none
//	timeout N             UDP mappings are forgotten after N seconds without traffic; 0: the default
//	alg on|off            on: SIP over UDP passes the ALG of alg.go
//	rtp on|off            off: RTP between the clients and the PBX is dropped, both ways
//	reset                 all of the above back to the start: cone, on, 0, 0, off, on
//	state                 tells the settings
//
// Every change replaces the rules at once and forgets the UDP mappings made so far, so that
// it applies to the flows already running as well. Nothing here touches the host: the rules
// live in the network namespace of the container.

import (
	"bufio"
	"fmt"
	"log"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
)

type labState struct {
	symmetric bool
	udpSip    bool
	maxUDP    int
	timeout   int
	alg       bool
	rtp       bool
}

func (s labState) String() string {
	mode := "cone"
	if s.symmetric {
		mode = "symmetric"
	}
	return fmt.Sprintf("mode=%s udp-sip=%s max-udp=%d timeout=%d alg=%s rtp=%s", mode, onOff(s.udpSip), s.maxUDP, s.timeout, onOff(s.alg), onOff(s.rtp))
}

func onOff(b bool) string {
	if b {
		return "on"
	}
	return "off"
}

var startState = labState{udpSip: true, rtp: true}

// The ALG listens here, on the client side of the box; SIP over UDP is sent to it while it is on.
const algPort = 5070

type box struct {
	mu            sync.Mutex
	state         labState
	client, inet  string // interface names
	clientAddress string
	pbx, stun     string
}

func runCtl(listen string) error {
	b := &box{
		state:         startState,
		clientAddress: os.Getenv("CLIENT_ADDRESS"),
		pbx:           os.Getenv("PBX_ADDRESS"),
		stun:          os.Getenv("STUN_ADDRESS"),
	}
	var err error
	if b.client, err = interfaceWith(b.clientAddress); err != nil {
		return err
	}
	if b.inet, err = interfaceWith(os.Getenv("INET_ADDRESS")); err != nil {
		return err
	}
	if err := b.apply(); err != nil {
		return err
	}
	go func() {
		if err := runAlg(net.JoinHostPort(b.clientAddress, strconv.Itoa(algPort)), net.JoinHostPort(b.pbx, "5060")); err != nil {
			log.Fatalf("alg: %v", err)
		}
	}()
	server, err := net.Listen("tcp4", listen)
	if err != nil {
		return err
	}
	log.Printf("ctl: listening on %s, %s", listen, b.state)
	for {
		conn, err := server.Accept()
		if err != nil {
			return err
		}
		go b.serve(conn)
	}
}

func interfaceWith(address string) (string, error) {
	interfaces, err := net.Interfaces()
	if err != nil {
		return "", err
	}
	for _, i := range interfaces {
		addresses, _ := i.Addrs()
		for _, a := range addresses {
			if n, ok := a.(*net.IPNet); ok && n.IP.String() == address {
				return i.Name, nil
			}
		}
	}
	return "", fmt.Errorf("no interface has the address %q", address)
}

func (b *box) serve(conn net.Conn) {
	defer conn.Close()
	lines := bufio.NewScanner(conn)
	for lines.Scan() {
		reply := b.command(strings.Fields(lines.Text()))
		log.Printf("ctl: %q -> %s", lines.Text(), reply)
		fmt.Fprintln(conn, reply)
	}
}

func (b *box) command(words []string) string {
	b.mu.Lock()
	defer b.mu.Unlock()
	if len(words) == 0 {
		return "error empty command"
	}
	next := b.state
	switch {
	case words[0] == "state" && len(words) == 1:
		return "ok " + b.state.String()
	case words[0] == "reset" && len(words) == 1:
		next = startState
	case words[0] == "mode" && len(words) == 2 && (words[1] == "cone" || words[1] == "symmetric"):
		next.symmetric = words[1] == "symmetric"
	case (words[0] == "udp-sip" || words[0] == "alg" || words[0] == "rtp") && len(words) == 2 && (words[1] == "on" || words[1] == "off"):
		switch words[0] {
		case "alg":
			next.alg = words[1] == "on"
		case "rtp":
			next.rtp = words[1] == "on"
		default:
			next.udpSip = words[1] == "on"
		}
	case (words[0] == "max-udp" || words[0] == "timeout") && len(words) == 2:
		n, err := strconv.Atoi(words[1])
		if err != nil || n < 0 {
			return "error not a number: " + words[1]
		}
		if words[0] == "max-udp" {
			next.maxUDP = n
		} else {
			next.timeout = n
		}
	default:
		return "error unknown command: " + strings.Join(words, " ")
	}
	previous := b.state
	b.state = next
	if err := b.apply(); err != nil {
		b.state = previous
		return "error " + err.Error()
	}
	return "ok " + b.state.String()
}

// apply replaces the rules of the box with those of its state and forgets the UDP mappings.
func (b *box) apply() error {
	s := b.state
	var r strings.Builder
	r.WriteString("table ip natlab\ndelete table ip natlab\ntable ip natlab {\n")
	if s.timeout > 0 {
		fmt.Fprintf(&r, "  ct timeout udp_short {\n    protocol udp\n    l3proto ip\n    policy = { unreplied : %d, replied : %d }\n  }\n", s.timeout, s.timeout)
		r.WriteString("  chain timeouts {\n    type filter hook prerouting priority filter; policy accept;\n    meta l4proto udp ct timeout set \"udp_short\"\n  }\n")
	}
	r.WriteString("  chain pre {\n    type nat hook prerouting priority dstnat; policy accept;\n")
	if s.alg {
		fmt.Fprintf(&r, "    iifname %q udp dport 5060 dnat to %s:%d\n", b.client, b.clientAddress, algPort)
	}
	fmt.Fprintf(&r, "    iifname %q udp dport { 5060, 5062, 22000-22099 } dnat to %s\n", b.client, b.pbx)
	fmt.Fprintf(&r, "    iifname %q tcp dport { 5060, 5038 } dnat to %s\n", b.client, b.pbx)
	fmt.Fprintf(&r, "    iifname %q udp dport 3478 dnat to %s\n", b.client, b.stun)
	r.WriteString("  }\n")
	// Dropped where the packets come in, whether they pass the box or go to the ALG on it.
	for _, hook := range []string{"forward", "input"} {
		fmt.Fprintf(&r, "  chain drop_%s {\n    type filter hook %s priority filter; policy accept;\n", hook, hook)
		if !s.udpSip {
			fmt.Fprintf(&r, "    iifname %q udp dport { 5060, %d } drop\n", b.client, algPort)
		}
		if s.maxUDP > 0 {
			fmt.Fprintf(&r, "    iifname %q meta l4proto udp meta length > %d drop\n", b.client, s.maxUDP)
		}
		if !s.rtp && hook == "forward" {
			r.WriteString("    udp dport 22000-22099 drop\n    udp sport 22000-22099 drop\n")
		}
		r.WriteString("  }\n")
	}
	random := ""
	if s.symmetric {
		random = " fully-random"
	}
	fmt.Fprintf(&r, "  chain post {\n    type nat hook postrouting priority srcnat; policy accept;\n    oifname %q masquerade%s\n  }\n}\n", b.inet, random)

	nft := exec.Command("nft", "-f", "-")
	nft.Stdin = strings.NewReader(r.String())
	if out, err := nft.CombinedOutput(); err != nil {
		return fmt.Errorf("nft: %v: %s", err, strings.TrimSpace(string(out)))
	}
	// Exits with 1 when there is nothing to delete.
	_ = exec.Command("conntrack", "-D", "-p", "udp").Run()
	return nil
}
