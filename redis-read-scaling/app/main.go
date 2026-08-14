// A read-serving API in front of Redis.
//
// The point of this file: Redis does NOT route reads to replicas. A client has
// to do it. This app discovers replicas by asking the primary (INFO
// replication) every few seconds, then round-robins reads across whatever it
// found. Scale replicas up or down and the pool follows within ~3s, with no
// restart and no config change.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/redis/go-redis/v9"
)

// The app's own view of what happened. Redis-side metrics tell you the nodes are
// busy; these tell you whether your users got an answer.
var (
	readsTotal = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "app_reads_total",
		Help: "Reads attempted, by outcome and which Redis served them.",
	}, []string{"outcome", "target"})

	readDuration = promauto.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "app_read_duration_seconds",
		Help:    "Read latency as the caller experiences it.",
		Buckets: []float64{.0005, .001, .002, .005, .01, .025, .05, .1, .25, .5, 1, 2.5},
	}, []string{"outcome"})

	poolSize = promauto.NewGauge(prometheus.GaugeOpts{
		Name: "app_read_pool_replicas",
		Help: "Replicas the app is currently sending reads to.",
	})
)

var (
	primaryAddr = env("REDIS_PRIMARY", "redis-primary:6379")
	keyCount    = envInt("KEY_COUNT", 10000)
	// readFromPrimary=true reproduces the common production mistake: replicas
	// exist, but every read still goes to the primary, so adding them changes
	// nothing. Flip it to watch the difference.
	readFromPrimary = env("READ_FROM_PRIMARY", "false") == "true"
)

// pool holds the current read targets. Replaced wholesale on each refresh.
type pool struct {
	mu       sync.RWMutex
	replicas []*redis.Client
	addrs    []string
	next     uint64
}

func (p *pool) set(clients []*redis.Client, addrs []string) {
	p.mu.Lock()
	old := p.replicas
	p.replicas, p.addrs = clients, addrs
	p.mu.Unlock()
	for _, c := range old {
		_ = c.Close()
	}
}

// pick returns the next replica round-robin, or nil if there are none.
func (p *pool) pick() (*redis.Client, string) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	if len(p.replicas) == 0 {
		return nil, ""
	}
	i := atomic.AddUint64(&p.next, 1) % uint64(len(p.replicas))
	return p.replicas[i], p.addrs[i]
}

func (p *pool) list() []string {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return append([]string{}, p.addrs...)
}

// slaveLine matches "slave0:ip=172.18.0.5,port=6379,state=online,..."
var slaveLine = regexp.MustCompile(`slave\d+:ip=([^,]+),port=(\d+),state=online`)

func main() {
	primary := redis.NewClient(&redis.Options{
		Addr:         primaryAddr,
		PoolSize:     256,
		MinIdleConns: 16,
	})
	p := &pool{}

	go refreshLoop(primary, p)

	mux := http.NewServeMux()

	// /read — the endpoint under load. Reads a random key from a replica.
	mux.HandleFunc("/read", func(w http.ResponseWriter, r *http.Request) {
		key := fmt.Sprintf("key:%d", rand.Intn(keyCount))

		client, addr := primary, primaryAddr
		if !readFromPrimary {
			if c, a := p.pick(); c != nil {
				client, addr = c, a
			}
		}

		start := time.Now()
		val, err := client.Get(r.Context(), key).Result()
		outcome := "ok"
		if err != nil && err != redis.Nil {
			outcome = "error"
		}
		readsTotal.WithLabelValues(outcome, addr).Inc()
		readDuration.WithLabelValues(outcome).Observe(time.Since(start).Seconds())

		w.Header().Set("X-Served-By", addr)
		if outcome == "error" {
			http.Error(w, err.Error(), http.StatusServiceUnavailable)
			return
		}
		fmt.Fprint(w, val)
	})

	mux.Handle("/metrics", promhttp.Handler())

	// /write — always goes to the primary. There is nowhere else to send it.
	mux.HandleFunc("/write", func(w http.ResponseWriter, r *http.Request) {
		key := fmt.Sprintf("key:%d", rand.Intn(keyCount))
		if err := primary.Set(r.Context(), key, time.Now().UnixNano(), 0).Err(); err != nil {
			http.Error(w, err.Error(), http.StatusServiceUnavailable)
			return
		}
		fmt.Fprint(w, "ok")
	})

	// /seed — populate the keyspace so reads have something to find.
	mux.HandleFunc("/seed", func(w http.ResponseWriter, r *http.Request) {
		ctx := r.Context()
		pipe := primary.Pipeline()
		for i := 0; i < keyCount; i++ {
			pipe.Set(ctx, fmt.Sprintf("key:%d", i), fmt.Sprintf("value-%d", i), 0)
			if i%1000 == 999 {
				if _, err := pipe.Exec(ctx); err != nil {
					http.Error(w, err.Error(), 500)
					return
				}
			}
		}
		if _, err := pipe.Exec(ctx); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		fmt.Fprintf(w, "seeded %d keys\n", keyCount)
	})

	// /pool — what the app currently believes the read pool is.
	// `watch -n1 curl -s localhost:8080/pool` is the clearest live view of
	// replicas joining and leaving.
	mux.HandleFunc("/pool", func(w http.ResponseWriter, r *http.Request) {
		addrs := p.list()
		serving := "replicas"
		if readFromPrimary || len(addrs) == 0 {
			serving = "primary only"
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"primary":       primaryAddr,
			"replicas":      addrs,
			"replica_count": len(addrs),
			"reads_served_by": serving,
		})
	})

	// /staleness — write to the primary, immediately read from a replica.
	// Demonstrates that replication is async: this returns a mismatch when the
	// replica hasn't caught up yet.
	mux.HandleFunc("/staleness", func(w http.ResponseWriter, r *http.Request) {
		ctx := r.Context()
		key, want := "staleness:probe", strconv.FormatInt(time.Now().UnixNano(), 10)
		if err := primary.Set(ctx, key, want, 0).Err(); err != nil {
			http.Error(w, err.Error(), 503)
			return
		}
		client, addr := p.pick()
		if client == nil {
			http.Error(w, "no replicas in the pool yet", 503)
			return
		}
		got, _ := client.Get(ctx, key).Result()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"replica": addr,
			"wrote":   want,
			"read":    got,
			"stale":   got != want,
		})
	})

	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, "ok")
	})

	srv := &http.Server{
		Addr:    ":8080",
		Handler: mux,
		// Long enough that the app isn't the thing timing out — we want to see
		// Redis saturate, not this server.
		ReadHeaderTimeout: 5 * time.Second,
	}
	log.Printf("listening on :8080  primary=%s  read_from_primary=%v", primaryAddr, readFromPrimary)
	log.Fatal(srv.ListenAndServe())
}

// refreshLoop asks the primary who its replicas are, every 3 seconds.
// This is roughly what a Sentinel-aware client does.
func refreshLoop(primary *redis.Client, p *pool) {
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		info, err := primary.Info(ctx, "replication").Result()
		cancel()

		if err != nil {
			log.Printf("refresh: %v", err)
			time.Sleep(3 * time.Second)
			continue
		}

		var addrs []string
		for _, m := range slaveLine.FindAllStringSubmatch(info, -1) {
			addrs = append(addrs, m[1]+":"+m[2])
		}

		if !sameAddrs(addrs, p.list()) {
			clients := make([]*redis.Client, 0, len(addrs))
			for _, a := range addrs {
				clients = append(clients, redis.NewClient(&redis.Options{
					Addr:         a,
					PoolSize:     256,
					MinIdleConns: 16,
				}))
			}
			p.set(clients, addrs)
			poolSize.Set(float64(len(addrs)))
			log.Printf("read pool now %d replica(s): %v", len(addrs), addrs)
		}
		time.Sleep(3 * time.Second)
	}
}

func sameAddrs(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	seen := map[string]int{}
	for _, x := range a {
		seen[x]++
	}
	for _, x := range b {
		seen[x]--
		if seen[x] < 0 {
			return false
		}
	}
	return true
}

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func envInt(k string, def int) int {
	if v := os.Getenv(k); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}
