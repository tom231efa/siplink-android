package ldapd

import (
	"container/list"
	"context"
	"sync"

	"siplink/internal/store"
)

// snapshot is the in-memory copy of one book at one revision (design §4.6).
type snapshot struct {
	bookID   int64
	revision int64
	nodes    []*node         // in sort_key order
	byID     map[int64]*node // entry id -> node
}

// cache keeps snapshots of the most recently used books, bounded by the total
// number of entries. Searches in flight keep the snapshot they started with.
type cache struct {
	st       *store.Store
	dit      *dit
	maxNodes int

	mu    sync.Mutex
	items map[int64]*list.Element // of *snapshot
	lru   *list.List              // front = most recently used
	total int
	loads map[int64]*sync.Mutex // one loader per book
}

func newCache(st *store.Store, d *dit, maxNodes int) *cache {
	return &cache{st: st, dit: d, maxNodes: maxNodes, items: map[int64]*list.Element{}, lru: list.New(),
		loads: map[int64]*sync.Mutex{}}
}

// lookup returns a cached snapshot of the book at its current revision, or nil.
func (c *cache) lookup(b *store.Book) *snapshot {
	c.mu.Lock()
	defer c.mu.Unlock()
	if el := c.items[b.ID]; el != nil {
		if s := el.Value.(*snapshot); s.revision == b.Revision {
			c.lru.MoveToFront(el)
			return s
		}
	}
	return nil
}

func (c *cache) loader(id int64) *sync.Mutex {
	c.mu.Lock()
	defer c.mu.Unlock()
	m := c.loads[id]
	if m == nil {
		m = &sync.Mutex{}
		c.loads[id] = m
	}
	return m
}

// get returns the snapshot of a book, loading it from the database when the cached
// one is missing or older than the book's revision.
func (c *cache) get(ctx context.Context, b *store.Book) (*snapshot, error) {
	if s := c.lookup(b); s != nil {
		return s, nil
	}
	l := c.loader(b.ID)
	l.Lock()
	defer l.Unlock()
	if s := c.lookup(b); s != nil {
		return s, nil // loaded by another search meanwhile
	}
	entries, rev, err := c.st.AllEntries(ctx, b.ID)
	if err != nil {
		return nil, err
	}
	s := &snapshot{bookID: b.ID, revision: rev, nodes: make([]*node, len(entries)), byID: make(map[int64]*node, len(entries))}
	for i, e := range entries {
		n := c.dit.entryNode(e, b.Slug)
		s.nodes[i] = n
		s.byID[e.ID] = n
	}
	c.put(s)
	return s, nil
}

func (c *cache) put(s *snapshot) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if el := c.items[s.bookID]; el != nil {
		c.total -= len(el.Value.(*snapshot).nodes)
		c.lru.Remove(el)
	}
	c.items[s.bookID] = c.lru.PushFront(s)
	c.total += len(s.nodes)
	// Drop least recently used books beyond the bound, never the one just loaded.
	for c.total > c.maxNodes && c.lru.Len() > 1 {
		el := c.lru.Back()
		old := el.Value.(*snapshot)
		c.lru.Remove(el)
		delete(c.items, old.bookID)
		c.total -= len(old.nodes)
	}
}

// stats returns the number of cached books and entries.
func (c *cache) stats() (books, entries int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.lru.Len(), c.total
}
