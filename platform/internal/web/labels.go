package web

import (
	"encoding/json"
	"fmt"
	"strings"
)

// actionLabels names audit actions in Chinese for the tables.
var actionLabels = map[string]string{
	"login.success":           "登录",
	"login.failure":           "登录失败",
	"logout":                  "退出",
	"admin.create":            "新建管理员",
	"admin.update":            "修改管理员",
	"admin.disable":           "停用管理员",
	"admin.enable":            "启用管理员",
	"admin.password_reset":    "重置管理员密码",
	"admin.password_change":   "修改本人密码",
	"region.create":           "新建区域",
	"region.update":           "修改区域",
	"region.delete":           "删除区域",
	"book.create":             "新建通讯录",
	"book.update":             "修改通讯录",
	"book.delete":             "删除通讯录",
	"book.import":             "导入通讯录",
	"book.export":             "导出通讯录",
	"directory.create":        "新建外部通讯录",
	"directory.update":        "修改外部通讯录",
	"directory.delete":        "删除外部通讯录",
	"entry.create":            "新建联系人",
	"entry.update":            "修改联系人",
	"entry.delete":            "删除联系人",
	"ldap_account.create":     "新建 LDAP 账号",
	"ldap_account.update":     "修改 LDAP 账号",
	"ldap_account.reset":      "重置 LDAP 账号密码",
	"ldap_account.delete":     "删除 LDAP 账号",
	"config_template.create":  "新建配置模板",
	"config_template.update":  "修改配置模板",
	"config_template.delete":  "删除配置模板",
	"key_template.create":     "新建功能键模板",
	"key_template.update":     "修改功能键模板",
	"key_template.import":     "导入功能键",
	"key_template.delete":     "删除功能键模板",
	"client_template.create":  "新建客户端模板",
	"client_template.update":  "修改客户端模板",
	"client_template.delete":  "删除客户端模板",
	"client.create":           "新建客户端",
	"client.update":           "修改客户端",
	"client.delete":           "删除客户端",
	"client.import":           "导入客户端",
	"client.unbind":           "重新生成二维码",
	"client.ldap_reset":       "重置 LDAP 凭据",
	"secret.view":             "查看密码",
	"secret.view_denied":      "查看密码（密码错误）",
	"device.enroll":           "设备绑定",
	"device.leave":            "设备移除远程配置",
	"backup.create":           "备份",
	"backup.failed":           "备份失败",
	"pki.reissue":             "重新签发服务器证书",
	"pki.own_import":          "启用自有证书",
	"pki.own_remove":          "停用自有证书",
	"pki.reminder":            "修改证书到期提醒",
	"login.second_failure":    "两步验证失败",
	"two_step.enable":         "启用两步验证",
	"two_step.disable":        "关闭两步验证",
	"passkey.create":          "添加通行密钥",
	"passkey.delete":          "删除通行密钥",
	"two_step.recovery_renew": "重新生成恢复码",
	"two_step.recovery_used":  "使用恢复码",
	"two_step.reset":          "重置两步验证",
	"two_step.policy":         "修改两步验证要求",
	"password.policy":         "修改密码强度检测",
	"backup.download":         "下载备份",
	"admin.email":             "修改邮箱",
	"admin.email_confirm":     "验证邮箱",
	"mail.update":             "修改邮件设置",
	"mail.test":               "发送测试邮件",
}

func actionLabel(action string) string {
	if l, ok := actionLabels[action]; ok {
		return l
	}
	return action
}

// objectLabels names the object types of the audit filter.
var objectLabels = map[string]string{
	"admin":           "管理员",
	"region":          "区域",
	"book":            "通讯录",
	"entry":           "联系人",
	"directory":       "外部通讯录",
	"ldap_account":    "LDAP 账号",
	"config_template": "配置模板",
	"key_template":    "功能键模板",
	"client_template": "客户端模板",
	"client":          "客户端",
	"backup":          "备份",
	"system":          "系统",
}

// fieldLabels name the keys of an audit entry's detail for the "改动" column; the
// stored keys stay as they are. Settings keys are the document's JSON names.
var fieldLabels = map[string]string{
	"name": "名称", "slug": "标识", "note": "备注", "books": "通讯录", "book": "通讯录",
	"mode": "方式", "password": "密码", "role": "角色", "regions": "区域", "region_id": "区域",
	"display_name": "显示名", "disabled": "停用", "allow_anonymous": "允许匿名", "entries": "联系人数",
	"count": "数量", "format": "格式", "surname": "姓", "given_name": "名", "organization": "单位",
	"department": "部门", "title": "职务", "phones": "电话", "mobiles": "手机", "email": "邮箱",
	"config_template_id": "配置模板", "key_template_id": "功能键模板", "client_template_id": "客户端模板", "key": "功能键", "op": "操作",
	"profile": "账号", "directory": "通讯录", "kind": "类型", "credentials": "凭据", "ca_certificates": "CA 证书", "codecs": "编解码器",
	"config_password": "配置密码", "refresh_minutes": "刷新间隔", "replace": "替换", "bound": "已绑定设备", "import": "批量导入", "seed": "测试数据", "error": "错误",
	"forward_always": "无条件转移", "forward_busy": "遇忙转移", "forward_no_answer": "无应答转移",
	"directory_password": "通讯录密码", "public_id": "LDAP 标识",
	"keepAlive": "后台保活", "intercomAutoAnswer": "对讲自动应答", "intercomSpeaker": "对讲免提",
	"verifyTlsCertificate": "校验 TLS 证书", "verifyTlsHostname": "校验主机名", "mediaSecurity": "媒体加密",
	"dndMode": "免打扰方式", "callWaiting": "呼叫等待", "rejectAnonymous": "拒接匿名", "dtmfMode": "DTMF 方式",
	"forwardAlways": "无条件转移", "forwardBusy": "遇忙转移", "forwardNoAnswer": "无应答转移",
	"forwardNoAnswerSeconds": "无应答等待", "features": "功能码", "videoCalls": "视频通话",
	"videoSize": "视频分辨率", "videoFrameRate": "帧率", "autoUpdateCheck": "自动检查更新",
	"userAgent": "客户端名称（User-Agent）", "adaptiveRateControl": "自适应速率控制", "echoCancellation": "回声消除",
	"ipv6": "允许 IPv6", "callRecording": "通话录音", "recordByDefault": "默认录音",
	"autoAnswer": "自动接听", "autoAnswerSeconds": "自动接听时间", "reminder_days": "提醒天数", "rescan": "需重新扫码", "issued": "重新签发",
	"issuer": "签发者", "names": "证书名称", "not_after": "到期", "fingerprint": "指纹", "https": "HTTPS", "ldap": "LDAP", "public": "公共 CA",
	"method": "验证方式", "required": "必须启用", "check": "密码强度检测", "encrypted": "加密", "host": "服务器", "port": "端口", "security": "加密方式", "username": "用户名",
	"from_address": "发件地址", "from_name": "发件人名称", "to": "收件地址",
	"bind_dn": "登录名", "base_dn": "搜索基准", "password_mode": "密码方式", "max_results": "最大结果数",
	"name_attributes": "姓名属性", "number_attributes": "号码属性", "name_filter": "姓名过滤器", "number_filter": "号码过滤器",
}

// fieldLabel is the Chinese name of an audit detail key (the key itself when unknown).
func fieldLabel(key string) string {
	if l, ok := fieldLabels[key]; ok {
		return l
	}
	return key
}

// regStateLabels name the RegState values a phone reports (§3.4).
var regStateLabels = []struct{ state, label string }{
	{"OK", "正常"}, {"FAILED", "失败"}, {"PROGRESS", "注册中"}, {"NONE", "未注册"}, {"DISABLED", "停用"},
}

// regSummary sums up the registrations of a report, e.g. "正常 2 · 失败 1".
func regSummary(raw string) string {
	var regs []struct {
		State string `json:"state"`
	}
	if json.Unmarshal([]byte(raw), &regs) != nil || len(regs) == 0 {
		return "—"
	}
	count := map[string]int{}
	for _, r := range regs {
		count[r.State]++
	}
	var parts []string
	for _, l := range regStateLabels {
		if n := count[l.state]; n > 0 {
			parts = append(parts, fmt.Sprintf("%s %d", l.label, n))
		}
	}
	return strings.Join(parts, " · ")
}
