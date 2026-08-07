package pool

import (
	"context"
	"sync"
	"testing"
	"time"
)

func newTestPool(max int) *Pool {
	ResetStats()
	return New(max, NewConn)
}

// STEP 3 — a returned connection gets handed out again instead of dialing.
func TestReuse(t *testing.T) {
	p := newTestPool(2)
	defer p.Close()

	c1, err := p.Get(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	p.Put(c1)

	c2, err := p.Get(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer p.Put(c2)

	if c1.ID != c2.ID {
		t.Errorf("expected the same connection back, got %d then %d", c1.ID, c2.ID)
	}
	if got := TotalCreated(); got != 1 {
		t.Errorf("expected 1 connection ever created, got %d", got)
	}
}

// STEP 3 — never dial more than max, no matter how many callers.
func TestMaxLimit(t *testing.T) {
	p := newTestPool(3)
	defer p.Close()

	var held []*Conn
	for i := 0; i < 3; i++ {
		c, err := p.Get(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		held = append(held, c)
	}

	// the 4th must block until something is returned
	done := make(chan *Conn, 1)
	go func() {
		c, err := p.Get(context.Background())
		if err == nil {
			done <- c
		}
	}()

	select {
	case <-done:
		t.Fatal("Get returned while the pool was exhausted — max is not enforced")
	case <-time.After(100 * time.Millisecond):
	}

	p.Put(held[0]) // free one slot

	select {
	case c := <-done:
		p.Put(c)
	case <-time.After(time.Second):
		t.Fatal("Get stayed blocked after a connection was returned")
	}

	for _, c := range held[1:] {
		p.Put(c)
	}
	if got := TotalCreated(); got != 3 {
		t.Errorf("expected max 3 connections created, got %d", got)
	}
}

// STEP 3a — an exhausted pool must respect context cancellation.
func TestContextTimeout(t *testing.T) {
	p := newTestPool(1)
	defer p.Close()

	c, err := p.Get(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer p.Put(c)

	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()

	start := time.Now()
	if _, err := p.Get(ctx); err == nil {
		t.Fatal("expected an error when the pool is exhausted and ctx expires")
	}
	if elapsed := time.Since(start); elapsed > 500*time.Millisecond {
		t.Errorf("Get ignored the context deadline (waited %v)", elapsed)
	}
}

// STEP 5 — Close must not leak idle connections.
func TestCloseClosesIdle(t *testing.T) {
	p := newTestPool(3)

	var held []*Conn
	for i := 0; i < 3; i++ {
		c, _ := p.Get(context.Background())
		held = append(held, c)
	}
	for _, c := range held {
		p.Put(c)
	}

	p.Close()

	if got := CurrentlyOpen(); got != 0 {
		t.Errorf("expected 0 open connections after Close, got %d", got)
	}
}

// The real test: hammer it and make sure the cap holds under concurrency.
func TestConcurrent(t *testing.T) {
	const max = 5
	p := newTestPool(max)
	defer p.Close()

	var wg sync.WaitGroup
	for i := 0; i < 200; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			c, err := p.Get(context.Background())
			if err != nil {
				t.Error(err)
				return
			}
			time.Sleep(time.Millisecond)
			p.Put(c)
		}()
	}
	wg.Wait()

	if got := TotalCreated(); got > max {
		t.Errorf("created %d connections, max is %d", got, max)
	}
	t.Logf("200 goroutines served by %d connections", TotalCreated())
}
