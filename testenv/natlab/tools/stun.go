package main

// A STUN server of the smallest kind (RFC 5389): every Binding request is answered with a
// Binding success response that carries the address it came from in XOR-MAPPED-ADDRESS.

import (
	"encoding/binary"
	"log"
	"net"
)

const (
	magicCookie    = 0x2112A442
	bindingRequest = 0x0001
	bindingSuccess = 0x0101
	xorMapped      = 0x0020
)

func runStun(listen []string) error {
	errs := make(chan error, len(listen))
	for _, address := range listen {
		conn, err := net.ListenPacket("udp4", address)
		if err != nil {
			return err
		}
		log.Printf("stun: listening on %s", address)
		go func() { errs <- serveStun(conn) }()
	}
	return <-errs
}

func serveStun(conn net.PacketConn) error {
	buffer := make([]byte, 1500)
	for {
		n, from, err := conn.ReadFrom(buffer)
		if err != nil {
			return err
		}
		source, ok := from.(*net.UDPAddr)
		if !ok || n < 20 || binary.BigEndian.Uint16(buffer[0:]) != bindingRequest || binary.BigEndian.Uint32(buffer[4:]) != magicCookie {
			continue
		}
		ip := source.IP.To4()
		if ip == nil {
			continue
		}
		answer := make([]byte, 32)
		binary.BigEndian.PutUint16(answer[0:], bindingSuccess)
		binary.BigEndian.PutUint16(answer[2:], 12)
		binary.BigEndian.PutUint32(answer[4:], magicCookie)
		copy(answer[8:20], buffer[8:20])
		binary.BigEndian.PutUint16(answer[20:], xorMapped)
		binary.BigEndian.PutUint16(answer[22:], 8)
		answer[25] = 0x01 // IPv4
		binary.BigEndian.PutUint16(answer[26:], uint16(source.Port)^uint16(magicCookie>>16))
		binary.BigEndian.PutUint32(answer[28:], binary.BigEndian.Uint32(ip)^magicCookie)
		if _, err := conn.WriteTo(answer, from); err != nil {
			log.Printf("stun: answer to %s: %v", from, err)
		}
		log.Printf("stun: %s", from)
	}
}
