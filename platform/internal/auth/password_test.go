package auth

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// cheapHashes lowers the cost of new hashes for one test.
func cheapHashes(t *testing.T) {
	saved := HashParams
	HashParams = Argon2Params{Memory: 64, Time: 1, Threads: 1}
	t.Cleanup(func() { HashParams = saved })
}

// TestHashingLimit: at most MaxHashing argon2id computations run at once, whether they
// hash, verify or verify the dummy.
func TestHashingLimit(t *testing.T) {
	cheapHashes(t)
	hash, _ := HashPassword("pw")
	var running, most atomic.Int32
	orig := idKey
	idKey = func(password, salt []byte, rounds, memory uint32, threads uint8, n uint32) []byte {
		now := running.Add(1)
		for m := most.Load(); now > m && !most.CompareAndSwap(m, now); m = most.Load() {
		}
		time.Sleep(20 * time.Millisecond)
		running.Add(-1)
		return orig(password, salt, rounds, memory, threads, n)
	}
	t.Cleanup(func() { idKey = orig })
	var wg sync.WaitGroup
	for i := range 24 {
		wg.Go(func() {
			switch i % 3 {
			case 0:
				HashPassword("x")
			case 1:
				VerifyPassword(context.Background(), hash, "pw")
			default:
				VerifyDummy(context.Background(), "pw")
			}
		})
	}
	wg.Wait()
	if n := most.Load(); n != MaxHashing {
		t.Errorf("at most %d computations at once, want %d", n, MaxHashing)
	}
}

// TestHashingWait: with every slot taken, a verification under a deadline (a login)
// gives up with ErrBusy, and a hash (password change, CLI) waits until a slot is free.
func TestHashingWait(t *testing.T) {
	cheapHashes(t)
	hash, _ := HashPassword("pw")
	VerifyDummy(context.Background(), "pw") // the dummy hash is made once, before the slots fill
	for range MaxHashing {
		hashSlots <- struct{}{}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	if _, err := VerifyPassword(ctx, hash, "pw"); !errors.Is(err, ErrBusy) {
		t.Errorf("verify: %v", err)
	}
	if err := VerifyDummy(ctx, "pw"); !errors.Is(err, ErrBusy) {
		t.Errorf("dummy: %v", err)
	}
	done := make(chan struct{})
	go func() {
		HashPassword("waits")
		close(done)
	}()
	select {
	case <-done:
		t.Fatal("hashed without a slot")
	case <-time.After(50 * time.Millisecond):
	}
	<-hashSlots
	<-done
	for range MaxHashing - 1 {
		<-hashSlots
	}
	if ok, err := VerifyPassword(context.Background(), hash, "pw"); !ok || err != nil {
		t.Errorf("after the slots are free: %v %v", ok, err)
	}
}
