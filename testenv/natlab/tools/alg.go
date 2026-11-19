package main

// A SIP ALG of the kind that breaks things, in user space. It relays SIP over UDP between the
// clients and the PBX, one upstream socket per client (as a NAT maps it), and on the way up
// writes its own outer address over the client's address wherever it stands: in the Via, the
// Contact and the SDP. It does not correct the Content-Length after that, and it does not
// restore anything in the answers: the client finds its Via changed, which is what the line
// checks look for.

import (
	"log"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

const algIdle = 5 * time.Minute

func runAlg(listen, upstream string) error {
	local, err := net.ResolveUDPAddr("udp4", listen)
	if err != nil {
		return err
	}
	server, err := net.ResolveUDPAddr("udp4", upstream)
	if err != nil {
		return err
	}
	conn, err := net.ListenUDP("udp4", local)
	if err != nil {
		return err
	}
	log.Printf("alg: %s -> %s", listen, upstream)
	var mu sync.Mutex
	sessions := map[string]*net.UDPConn{}
	buffer := make([]byte, 65535)
	for {
		n, client, err := conn.ReadFromUDP(buffer)
		if err != nil {
			return err
		}
		key := client.String()
		mu.Lock()
		up := sessions[key]
		if up == nil {
			if up, err = net.DialUDP("udp4", nil, server); err != nil {
				mu.Unlock()
				log.Printf("alg: %v", err)
				continue
			}
			sessions[key] = up
			go func(client *net.UDPAddr, up *net.UDPConn) {
				back := make([]byte, 65535)
				for {
					_ = up.SetReadDeadline(time.Now().Add(algIdle))
					m, err := up.Read(back)
					if err != nil {
						break
					}
					_, _ = conn.WriteToUDP(back[:m], client)
				}
				mu.Lock()
				delete(sessions, client.String())
				mu.Unlock()
				up.Close()
			}(client, up)
		}
		mu.Unlock()
		outer := up.LocalAddr().(*net.UDPAddr)
		_, _ = up.Write([]byte(rewrite(string(buffer[:n]), outer)))
	}
}

// rewrite puts [outer] wherever the message names the address it says it was sent from (the
// sent-by of its top Via).
func rewrite(message string, outer *net.UDPAddr) string {
	via := topViaSentBy(message)
	if via == "" {
		return message
	}
	host, _, err := net.SplitHostPort(via)
	if err != nil {
		host = via
	}
	message = strings.ReplaceAll(message, via, net.JoinHostPort(outer.IP.String(), strconv.Itoa(outer.Port)))
	return strings.ReplaceAll(message, host, outer.IP.String())
}

func topViaSentBy(message string) string {
	for _, line := range strings.Split(message, "\r\n") {
		name, value, found := strings.Cut(line, ":")
		if !found {
			continue
		}
		name = strings.ToLower(strings.TrimSpace(name))
		if name != "via" && name != "v" {
			continue
		}
		// SIP/2.0/UDP host:port;params
		fields := strings.Fields(strings.TrimSpace(value))
		if len(fields) < 2 {
			return ""
		}
		return strings.SplitN(fields[1], ";", 2)[0]
	}
	return ""
}
