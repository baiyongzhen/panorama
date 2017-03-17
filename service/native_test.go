package service

import (
	"flag"
	"fmt"
	"math/rand"
	"os"
	"testing"
	"time"

	dh "deephealth"
	"deephealth/client"
	"deephealth/store"
)

const (
	portstart = 10000
	portend   = 30000
)

var c *client.Client
var r = rand.New(rand.NewSource(time.Now().UnixNano()))
var (
	remote = flag.Bool("remote", false, "whether to perform remote service test or not")
	faddr  = flag.String("addr", "localhost:30000", "use this address instead of localhost")
)

func BenchmarkAddReport(b *testing.B) {
	o := dh.NewObservation(time.Now(), "cpu", "disk", "network")
	o.SetMetric("cpu", dh.UNHEALTHY, 30)
	o.SetMetric("disk", dh.HEALTHY, 90)
	o.SetMetric("network", dh.HEALTHY, 95)
	report := &dh.Report{
		Observer:    "XFE_2",
		Subject:     "TS_2",
		Observation: *o,
	}
	var reply int
	for i := 0; i < b.N; i++ {
		c.AddReport(report, &reply)
	}
}

func TestMain(m *testing.M) {
	flag.Parse()
	var addr string
	if !*remote {
		port := portstart + int(r.Intn(portend-portstart))
		addr = fmt.Sprintf("localhost:%d", port)
		storage := store.NewRawHealthStorage("TS_1", "TS_2", "TS_3", "TS_4")
		dh.SetLogLevel(dh.ErrorLevel)
		hs := NewHealthService(addr, "XFE_1", storage)
		hs.Start()
	} else {
		addr = *faddr
	}
	c = client.NewClient(addr, false)
	os.Exit(m.Run())
}