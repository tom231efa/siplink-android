package auth

import (
	"testing"

	"siplink/internal/store"
)

// TestPermissionMatrix checks every row of design §2.4 for both roles and for an
// object in the administrator's own region, in another region, and global.
func TestPermissionMatrix(t *testing.T) {
	own, other := int64(1), int64(2)
	super := ActorOf(&store.Admin{ID: 1, Role: store.RoleSuper})
	region := ActorOf(&store.Admin{ID: 2, Role: store.RoleRegion, RegionIDs: []int64{own}})
	type want struct{ own, other, global bool }
	rows := []struct {
		act          Action
		super, local want
	}{
		{RegionList, want{true, true, false}, want{true, false, false}},
		{RegionManage, want{true, true, true}, want{false, false, false}},
		{AdminManage, want{true, true, true}, want{false, false, false}},
		{OwnPassword, want{true, true, true}, want{true, true, true}},
		{ClientManage, want{true, true, false}, want{true, false, false}},
		{ClientViewSecrets, want{true, true, false}, want{true, false, false}},
		{TemplateView, want{true, true, true}, want{true, false, true}},
		{TemplateEdit, want{true, true, true}, want{true, false, false}},
		{TemplateUse, want{true, true, true}, want{true, false, true}},
		{BookView, want{true, true, true}, want{true, false, true}},
		{BookEdit, want{true, true, true}, want{true, false, false}},
		{BookUse, want{true, true, true}, want{true, false, true}},
		{LDAPAccountManage, want{true, true, true}, want{true, false, false}},
		{System, want{true, true, true}, want{false, false, false}},
		{GroupManage, want{true, true, false}, want{true, false, false}},
	}
	for _, row := range rows {
		for _, c := range []struct {
			name  string
			actor Actor
			w     want
		}{{"super", super, row.super}, {"region", region, row.local}} {
			if got := c.actor.Can(row.act, &own); got != c.w.own {
				t.Errorf("%s %v own: %v", c.name, row.act, got)
			}
			if got := c.actor.Can(row.act, &other); got != c.w.other {
				t.Errorf("%s %v other: %v", c.name, row.act, got)
			}
			if got := c.actor.Can(row.act, nil); got != c.w.global {
				t.Errorf("%s %v global: %v", c.name, row.act, got)
			}
		}
	}
	two := ActorOf(&store.Admin{ID: 3, Role: store.RoleRegion, RegionIDs: []int64{1, 2}})
	if !super.CanMoveClient(1, 3) || !two.CanMoveClient(1, 2) || region.CanMoveClient(1, 2) || two.CanMoveClient(2, 3) {
		t.Error("client move rule")
	}
	if !super.CanChangeTag(true) || !region.CanChangeTag(false) || region.CanChangeTag(true) {
		t.Error("tag change rule")
	}
	a2, a9 := int64(2), int64(9)
	if !region.CanSeeAudit(&own, &a9) || region.CanSeeAudit(&other, &a9) || !region.CanSeeAudit(&other, &a2) ||
		region.CanSeeAudit(nil, nil) || !super.CanSeeAudit(nil, nil) {
		t.Error("audit visibility rule")
	}
}
