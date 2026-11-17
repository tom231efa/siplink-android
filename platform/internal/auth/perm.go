package auth

import "siplink/internal/store"

// Actor is an administrator as the permission matrix sees them (design §2.4).
type Actor struct {
	ID      int64
	Super   bool
	Regions map[int64]bool
}

// ActorOf builds the actor of an administrator.
func ActorOf(a *store.Admin) Actor {
	act := Actor{ID: a.ID, Super: a.Role == store.RoleSuper, Regions: map[int64]bool{}}
	if !act.Super {
		for _, id := range a.RegionIDs {
			act.Regions[id] = true
		}
	}
	return act
}

// Action is one row of the permission matrix.
type Action int

// The rows of §2.4. The region argument of Can is the object's region (nil = global).
const (
	RegionList        Action = iota // see a region in lists
	RegionManage                    // create / rename / delete regions
	AdminManage                     // list / create / disable / reset / assign regions of administrators
	OwnPassword                     // change one's own password
	ClientManage                    // list / view / create / edit / delete clients, QR, unbind, import, print
	ClientViewSecrets               // view stored passwords of a client (after re-entering one's password)
	TemplateView                    // view configuration / key / client templates
	TemplateEdit                    // create / edit / delete templates
	TemplateUse                     // pick a template in another object
	BookView                        // view entries, export
	BookEdit                        // create / edit entries / import / delete books
	BookUse                         // refer to a book from a template or grant it to an LDAP account
	LDAPAccountManage               // manage LDAP service accounts
	System                          // system page: backup, instance settings
)

var actionNames = [...]string{"RegionList", "RegionManage", "AdminManage", "OwnPassword", "ClientManage",
	"ClientViewSecrets", "TemplateView", "TemplateEdit", "TemplateUse", "BookView", "BookEdit", "BookUse",
	"LDAPAccountManage", "System"}

func (a Action) String() string {
	if int(a) < len(actionNames) {
		return actionNames[a]
	}
	return "Action?"
}

// own reports whether region is one of the actor's regions (never true for global).
func (a Actor) own(region *int64) bool { return region != nil && a.Regions[*region] }

// Can reports whether the actor may perform act on an object of region (nil = global).
func (a Actor) Can(act Action, region *int64) bool {
	switch act {
	case OwnPassword:
		return true
	case RegionManage, AdminManage, System:
		return a.Super
	}
	if a.Super {
		// Clients always belong to a region; a global client does not exist.
		if (act == ClientManage || act == ClientViewSecrets || act == RegionList) && region == nil {
			return false
		}
		return true
	}
	switch act {
	case RegionList, ClientManage, ClientViewSecrets, TemplateEdit, BookEdit, LDAPAccountManage:
		return a.own(region)
	case TemplateView, TemplateUse, BookView, BookUse:
		return region == nil || a.own(region)
	}
	return false
}

// CanMoveClient: super administrators anywhere, region administrators only between
// their own regions.
func (a Actor) CanMoveClient(from, to int64) bool {
	return a.Super || a.Regions[from] && a.Regions[to]
}

// CanSeeAudit reports whether an audit entry is visible: super administrators see
// all, region administrators entries of their regions plus their own actions.
func (a Actor) CanSeeAudit(entryRegion, entryAdmin *int64) bool {
	return a.Super || a.own(entryRegion) || entryAdmin != nil && *entryAdmin == a.ID
}

// RegionIDs returns the actor's regions; nil for super administrators (all).
func (a Actor) RegionIDs() []int64 {
	if a.Super {
		return nil
	}
	out := make([]int64, 0, len(a.Regions))
	for id := range a.Regions {
		out = append(out, id)
	}
	return out
}
