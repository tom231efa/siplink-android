// natlab: the tools of the NAT lab (testenv/natlab).
//
//	natlab ctl LISTEN          the NAT box: sets up its rules and takes commands on LISTEN (see ctl.go)
//	natlab stun LISTEN...      a STUN server: answers Binding requests with the address it saw
//	natlab alg LISTEN UPSTREAM a SIP ALG as a broken router has it (see alg.go)
package main

import (
	"fmt"
	"os"
)

func main() {
	if len(os.Args) < 3 {
		fmt.Fprintln(os.Stderr, "usage: natlab ctl LISTEN | stun LISTEN... | alg LISTEN UPSTREAM")
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "ctl":
		err = runCtl(os.Args[2])
	case "stun":
		err = runStun(os.Args[2:])
	case "alg":
		if len(os.Args) != 4 {
			err = fmt.Errorf("alg needs LISTEN and UPSTREAM")
		} else {
			err = runAlg(os.Args[2], os.Args[3])
		}
	default:
		err = fmt.Errorf("unknown command %q", os.Args[1])
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "natlab:", err)
		os.Exit(1)
	}
}
