package pool

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
)

var ErrPoolClosed = errors.New("pool is closed")

// Fake connection so the pool can be tested without I/O.

var connCounter atomic.Int64
var openCounter atomic.Int64

type Conn struct {
	ID     int64
	closed bool
}

func NewConn() (*Conn, error) {
	openCounter.Add(1)
	return &Conn{ID: connCounter.Add(1)}, nil
}

func (c *Conn) Close() error {
	if !c.closed {
		c.closed = true
		openCounter.Add(-1)
	}
	return nil
}

func TotalCreated() int64  { return connCounter.Load() }
func CurrentlyOpen() int64 { return openCounter.Load() }
func ResetStats()          { connCounter.Store(0); openCounter.Store(0) }

// Pool hands out at most `max` connections, blocking callers when none are free.
type Pool struct {
	mu      sync.Mutex
	cond    *sync.Cond // signalled when a connection returns
	queue   []*Conn    // idle connections
	created int        // how many exist in total
	max     int
	closed  bool
	factory func() (*Conn, error)
}

// STEP 1: build the struct. cond must be sync.NewCond(&p.mu).
func New(max int, factory func() (*Conn, error)) *Pool {
	panic("TODO step 1")
}

// STEP 2: the blocking queue.
//
//	lock, then loop:
//	  closed              -> ErrPoolClosed
//	  ctx expired         -> ctx.Err()
//	  queue not empty     -> pop the last one, return it
//	  created < max       -> created++, unlock, dial, return
//	  otherwise           -> p.cond.Wait()
//
// Wait() releases the lock while parked and reacquires it on wake, so the loop
// must re-check every condition after it returns.
//
// STEP 3: cond.Wait() ignores ctx. Before locking, start a goroutine that
// Broadcasts when ctx.Done() fires, so parked callers wake and see the
// expired ctx. Stop it with a done channel before returning.
func (p *Pool) Get(ctx context.Context) (*Conn, error) {
	panic("TODO step 2")
}

// STEP 4: closed -> close the conn and created--. Otherwise append to the
// queue and Signal one waiter.
func (p *Pool) Put(c *Conn) {
	panic("TODO step 4")
}

// STEP 5: mark closed, close everything in the queue, Broadcast so parked
// callers wake up and error out.
func (p *Pool) Close() {
	panic("TODO step 5")
}
