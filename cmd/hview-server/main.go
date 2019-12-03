package main

import (
	"bufio"
	"flag"
	"fmt"
	"io"
	"math/rand"
	"os"
	"os/signal"
	"runtime/pprof"
	"strings"
	"syscall"
	"time"

	"panorama/service"
	dt "panorama/types"
	du "panorama/util"
)

var (
	rc         = flag.String("config", "", "use config file to initialize service")
	addr       = flag.String("addr", "localhost", "server listen address")
	dbfile     = flag.String("dbfile", "deephealth.db", "database file of the local observations")
	portstart  = flag.Int("port_start", 10000, "start of port range for a random port")
	portend    = flag.Int("port_end", 30000, "end of port range for a random port")
	cpuprofile = flag.String("cpuprofile", "", "write CPU profiling to file")
	memusage   = flag.Bool("mem_usage", false, "periodically dump memory usage")
)

var r = rand.New(rand.NewSource(time.Now().UnixNano()))

func main() {
	flag.Usage = func() {
		fmt.Printf("Usage: %s [options] [ID]\n", os.Args[0])
		flag.PrintDefaults()
	}

	flag.Parse()
	config := new(dt.HealthServerConfig)
	var err error
	if len(*rc) > 0 {
		err = dt.LoadConfig(*rc, config)
		if err != nil {
			panic(err)
		}
		du.SetLogLevelString(config.LogLevel)
		myaddr, ok := config.Peers[config.Id]
		if !ok {
			panic("Id is not present in peers")
		}
		if len(config.Addr) == 0 {
			config.Addr = myaddr
		} else if config.Addr != myaddr {
			panic("Addr is not the same as the one in peers")
		}
	} else {
		faddr := *addr
		if !strings.ContainsAny(*addr, ":") {
			port := *portstart + int(r.Intn(*portend-*portstart))
			faddr = fmt.Sprintf("%s:%d", faddr, port)
		}
		args := flag.Args()
		if len(args) != 1 {
			flag.Usage()
			os.Exit(1)
		}
		config = &dt.HealthServerConfig{
			Addr:   faddr,
			Id:     args[0],
			DBFile: *dbfile,
		}
	}
	if *memusage || config.DumpMemUsage {
		memf, err := os.OpenFile("memusage.csv", os.O_RDWR|os.O_CREATE, 0644)
		if err != nil {
			fmt.Println("Failed to create memory usage stat file")
		} else {
			w := bufio.NewWriter(memf)
			fmt.Fprintf(w, "alloc,total_alloc,sys,gc\n")
			go func(w io.Writer) {
				for {
					du.PrintMemUsage(w)
					time.Sleep(5 * time.Second)
				}
			}(w)
		}
	}

	gs := service.NewHealthGServer(config)
	errch := make(chan error)

	if len(*cpuprofile) > 0 {
		f, err := os.Create(*cpuprofile)
		if err != nil {
			fmt.Errorf("Failed to create profile\n")
		}
		pprof.StartCPUProfile(f)
		sigs := make(chan os.Signal, 1)
		signal.Notify(sigs, os.Interrupt, syscall.SIGTERM)
		go func() {
			sig := <-sigs
			fmt.Printf("got signal %v, clean up before shutdown...\n", sig)
			gs.Stop(true)
			pprof.StopCPUProfile()
			os.Exit(0)
		}()
	}

	fmt.Printf("Starting health service at %s with config %v\n", config.Addr, config)
	gs.Start(errch)
	<-errch
	fmt.Println("Encountered error, exit.")
	os.Exit(1)
}
