# Glossary of the console's terms (DESIGN §5.5)

Every catalog in `internal/i18n/locales/` uses these words for these terms, in labels, titles,
buttons and sentences alike (inflected where the grammar needs it: Russian cases, Arabic
definite article and number, French and Spanish plural). Simplified Chinese is the source. Terms
not listed keep their usual technical meaning; the standard ones stay untranslated: SIP, TLS,
LDAP, StartTLS / STARTTLS, SRTP, DTMF, CSV, LDIF, QR, CA, DNS, URL, PEM, TOTP, SMTP, HTTPS,
User-Agent, IPv6, UDP, TCP, Base DN, Bind DN, STUN.

## Style per language

| Code | Buttons and menu items | Quotes around names | List separator | Notes |
|---|---|---|---|---|
| `zh-Hans` | as today | 「…」 | 、 | the source text |
| `en` | Sentence case, imperative ("Save", "New client") | “…” | `, ` | US spelling; "sign in", "sign out" |
| `ja` | noun or 〜する form ("保存", "新規クライアント") | 「…」 | 、 | です・ます in sentences; no spaces around Latin words except where Japanese UIs put them |
| `ar` | imperative or verbal noun ("حفظ", "عميل جديد") | «…» | `، ` | Modern Standard Arabic; Western (ASCII) digits; technical terms in Latin letters stay as they are |
| `fr` | infinitive ("Enregistrer", "Nouveau client") | « … » (narrow no-break spaces inside) | `, ` | a no-break space before `: ; ? !`; vouvoiement |
| `zh-Hant` | as zh-Hans, Taiwan wording | 「…」 | 、 | Taiwan terms (用戶端, 範本, 憑證, 帳號, 設定) |
| `ru` | infinitive or imperative noun phrase ("Сохранить", "Новый клиент") | «…» | `, ` | «вы» form; ё where it belongs |
| `es` | infinitive ("Guardar", "Nuevo cliente") | «…» | `, ` | usted form; neutral (not regional) Spanish |

Counts are never inflected: a label followed by the number ("Clients: 15"), never "15 clients"
(DESIGN §5.5). Chinese and Japanese may keep a counter inside the sentence.

## Terms

| 中文（简体） | English | 日本語 | العربية | Français | 中文（繁體） | Русский | Español |
|---|---|---|---|---|---|---|---|
| SipLink 管理平台 | SipLink Management Platform | SipLink 管理プラットフォーム | منصة إدارة SipLink | Plateforme de gestion SipLink | SipLink 管理平台 | Платформа управления SipLink | Plataforma de gestión SipLink |
| 管理平台 (beside the brand) | Management | 管理 | الإدارة | Gestion | 管理平台 | Управление | Gestión |
| 概览 | Overview | 概要 | نظرة عامة | Vue d'ensemble | 概覽 | Обзор | Resumen |
| 设备 (sidebar group) | Devices | デバイス | الأجهزة | Appareils | 裝置 | Устройства | Dispositivos |
| 管理 (sidebar group) | Administration | 管理 | الإدارة | Administration | 管理 | Администрирование | Administración |
| 区域 | Region | リージョン | منطقة | Région | 區域 | Регион | Región |
| 全部区域 | All regions | すべてのリージョン | كل المناطق | Toutes les régions | 全部區域 | Все регионы | Todas las regiones |
| 全局 | Global | グローバル | عام | Global | 全域 | Глобальный | Global |
| 客户端 | Client | クライアント | عميل | Client | 用戶端 | Клиент | Cliente |
| 分组（客户端） | Group | グループ | مجموعة | Groupe | 分組 | Группа | Grupo |
| 标签（客户端） | Tag | タグ | وسم | Étiquette | 標籤 | Метка | Etiqueta |
| 分组与标签 (page) | Groups and tags | グループとタグ | المجموعات والوسوم | Groupes et étiquettes | 分組與標籤 | Группы и метки | Grupos y etiquetas |
| 颜色（标签） | Color | 色 | اللون | Couleur | 顏色 | Цвет | Color |
| 客户端模板 | Client template | クライアントテンプレート | قالب العميل | Modèle de client | 用戶端範本 | Шаблон клиента | Plantilla de cliente |
| 配置模板 | Configuration template | 設定テンプレート | قالب الإعدادات | Modèle de configuration | 設定範本 | Шаблон конфигурации | Plantilla de configuración |
| 功能键模板 | Key template | キーテンプレート | قالب المفاتيح | Modèle de touches | 功能鍵範本 | Шаблон клавиш | Plantilla de teclas |
| 功能键 / 快捷键 | Key | キー | مفتاح | Touche | 功能鍵 | Клавиша | Tecla |
| 模板 | Template | テンプレート | قالب | Modèle | 範本 | Шаблон | Plantilla |
| 通讯录 | Directory | 電話帳 | دليل | Annuaire | 通訊錄 | Справочник | Directorio |
| 内部（通讯录） | Internal | 内部 | داخلي | Interne | 內部 | Внутренний | Interno |
| 托管通讯录 | Hosted directory | 内部電話帳 | الدليل المستضاف | Annuaire interne | 託管通訊錄 | Внутренний справочник | Directorio alojado |
| 外部（通讯录） | External | 外部 | خارجي | Externe | 外部 | Внешний | Externo |
| 联系人 | Contact | 連絡先 | جهة اتصال | Contact | 聯絡人 | Контакт | Contacto |
| LDAP 账号 | LDAP account | LDAP アカウント | حساب LDAP | Compte LDAP | LDAP 帳號 | Учётная запись LDAP | Cuenta LDAP |
| 账号（SIP） | Account | アカウント | حساب | Compte | 帳號 | Учётная запись | Cuenta |
| 会议室 | Conference room | 会議室 | غرفة المؤتمر | Salle de conférence | 會議室 | Конференц-комната | Sala de conferencias |
| 线路 | Line | 回線 | خط | Ligne | 線路 | Линия | Línea |
| 管理员 | Administrator | 管理者 | مسؤول | Administrateur | 管理員 | Администратор | Administrador |
| 超级管理员 | Super administrator | スーパー管理者 | مسؤول أعلى | Super-administrateur | 超級管理員 | Суперадминистратор | Superadministrador |
| 区域管理员 | Region administrator | リージョン管理者 | مسؤول منطقة | Administrateur de région | 區域管理員 | Администратор региона | Administrador de región |
| 审计日志 | Audit log | 監査ログ | سجل التدقيق | Journal d'audit | 稽核日誌 | Журнал аудита | Registro de auditoría |
| 系统 | System | システム | النظام | Système | 系統 | Система | Sistema |
| 邮件 | Mail | メール | البريد | E-mail | 郵件 | Почта | Correo |
| 两步验证 | Two-step verification | 2 段階認証 | التحقق بخطوتين | Validation en deux étapes | 兩步驟驗證 | Двухэтапная проверка | Verificación en dos pasos |
| 通行密钥 | Passkey | パスキー | مفتاح المرور | Clé d'accès | 通行密鑰 | Ключ доступа | Llave de acceso |
| 恢复码 | Recovery code | リカバリーコード | رمز الاسترداد | Code de récupération | 復原碼 | Код восстановления | Código de recuperación |
| 验证码 | Code | 確認コード | رمز التحقق | Code de vérification | 驗證碼 | Код подтверждения | Código de verificación |
| 身份验证器 | Authenticator | 認証アプリ | تطبيق المصادقة | Authentificateur | 驗證器 | Аутентификатор | Autenticador |
| 邮箱验证码 | E-mail code | メール確認コード | رمز التحقق بالبريد | Code par e-mail | 電子郵件驗證碼 | Код по почте | Código por correo |
| 内置 CA | Built-in CA | 内蔵 CA | CA المدمجة | CA intégrée | 內建 CA | Встроенный CA | CA integrada |
| 自有证书 | Own certificate | 独自証明書 | شهادة خاصة | Certificat propre | 自有憑證 | Собственный сертификат | Certificado propio |
| 证书 | Certificate | 証明書 | شهادة | Certificat | 憑證 | Сертификат | Certificado |
| 指纹 | Fingerprint | フィンガープリント | البصمة | Empreinte | 指紋 | Отпечаток | Huella digital |
| 二维码 | QR code | QR コード | رمز QR | Code QR | QR 碼 | QR-код | Código QR |
| 重新生成二维码 | Regenerate QR code | QR コードを再生成 | إعادة إنشاء رمز QR | Régénérer le code QR | 重新產生 QR 碼 | Создать QR-код заново | Regenerar código QR |
| 绑定（手机） | Bind | 紐付け | ربط | Associer | 綁定 | Привязать | Vincular |
| 已绑定 / 待绑定 | Bound / Pending | 紐付け済み / 未紐付け | مرتبط / قيد الانتظار | Associé / En attente | 已綁定 / 待綁定 | Привязан / Ожидает | Vinculado / Pendiente |
| 离线 | Offline | オフライン | غير متصل | Hors ligne | 離線 | Не в сети | Sin conexión |
| 远程配置 | Remote configuration | リモート設定 | الإعداد عن بُعد | Configuration à distance | 遠端設定 | Удалённая настройка | Configuración remota |
| 下发 | Provision (hand out) | 配布 | توزيع | Distribuer | 下發 | Передавать | Distribuir |
| 同步 | Sync | 同期 | مزامنة | Synchronisation | 同步 | Синхронизация | Sincronización |
| 注册（SIP） | Registration | 登録 | التسجيل | Enregistrement | 註冊 | Регистрация | Registro |
| 用户名 | User name | ユーザー名 | اسم المستخدم | Nom d'utilisateur | 使用者名稱 | Имя пользователя | Nombre de usuario |
| 密码 | Password | パスワード | كلمة المرور | Mot de passe | 密碼 | Пароль | Contraseña |
| 配置密码 | Configuration password | 設定パスワード | كلمة مرور الإعدادات | Mot de passe de configuration | 設定密碼 | Пароль конфигурации | Contraseña de configuración |
| 加密口令 | Passphrase | パスフレーズ | عبارة المرور | Phrase secrète | 加密口令 | Парольная фраза | Frase de contraseña |
| 显示名 | Display name | 表示名 | الاسم المعروض | Nom affiché | 顯示名稱 | Отображаемое имя | Nombre visible |
| 认证用户 | Auth user | 認証ユーザー | مستخدم المصادقة | Utilisateur d'authentification | 驗證使用者 | Пользователь аутентификации | Usuario de autenticación |
| 域名 | Domain | ドメイン | النطاق | Domaine | 網域 | Домен | Dominio |
| 服务器 | Server | サーバー | الخادم | Serveur | 伺服器 | Сервер | Servidor |
| 端口 | Port | ポート | المنفذ | Port | 連接埠 | Порт | Puerto |
| 传输方式 | Transport | トランスポート | النقل | Transport | 傳輸方式 | Транспорт | Transporte |
| 搜索基准 | Base DN | Base DN | Base DN | Base DN | Base DN | Base DN | Base DN |
| 编解码器 | Codecs | コーデック | برامج الترميز | Codecs | 編解碼器 | Кодеки | Códecs |
| 呼叫转移 | Call forwarding | 転送 | تحويل المكالمات | Renvoi d'appel | 來電轉接 | Переадресация | Desvío de llamadas |
| 免打扰 | Do not disturb | おやすみモード | عدم الإزعاج | Ne pas déranger | 勿擾 | Не беспокоить | No molestar |
| 对讲 | Intercom | インターコム | الاتصال الداخلي | Interphone | 對講 | Интерком | Intercomunicador |
| 保活 | Keep-alive | キープアライブ | الإبقاء على الاتصال | Maintien en vie | 保活 | Поддержание активности | Mantener activo |
| 刷新间隔 | Refresh interval | 更新間隔 | فاصل التحديث | Intervalle d'actualisation | 重新整理間隔 | Интервал обновления | Intervalo de actualización |
| 备份 | Backup | バックアップ | نسخة احتياطية | Sauvegarde | 備份 | Резервная копия | Copia de seguridad |
| 外观 | Appearance | 外観 | المظهر | Apparence | 外觀 | Оформление | Apariencia |
| 语言 | Language | 言語 | اللغة | Langue | 語言 | Язык | Idioma |
| 我的账号 | My account | マイアカウント | حسابي | Mon compte | 我的帳號 | Моя учётная запись | Mi cuenta |
| 登录 | Sign in | ログイン | تسجيل الدخول | Se connecter | 登入 | Войти | Iniciar sesión |
| 退出 | Sign out | ログアウト | تسجيل الخروج | Se déconnecter | 登出 | Выйти | Cerrar sesión |
| 名称 | Name | 名前 | الاسم | Nom | 名稱 | Название | Nombre |
| 备注 | Note | メモ | ملاحظة | Note | 備註 | Примечание | Nota |
| 新建 | New | 新規 | جديد | Nouveau | 新增 | Создать | Nuevo |
| 保存 | Save | 保存 | حفظ | Enregistrer | 儲存 | Сохранить | Guardar |
| 删除 | Delete | 削除 | حذف | Supprimer | 刪除 | Удалить | Eliminar |
| 取消 | Cancel | キャンセル | إلغاء | Annuler | 取消 | Отмена | Cancelar |
| 编辑 | Edit | 編集 | تعديل | Modifier | 編輯 | Изменить | Editar |
| 导入 / 导出 | Import / Export | インポート / エクスポート | استيراد / تصدير | Importer / Exporter | 匯入 / 匯出 | Импорт / Экспорт | Importar / Exportar |
| 下载 / 上传 | Download / Upload | ダウンロード / アップロード | تنزيل / رفع | Télécharger / Téléverser | 下載 / 上傳 | Скачать / Загрузить | Descargar / Subir |
| 启用 / 停用 | Enable / Disable | 有効にする / 無効にする | تفعيل / تعطيل | Activer / Désactiver | 啟用 / 停用 | Включить / Отключить | Activar / Desactivar |
| 已设置 / 未设置 | Set / Not set | 設定済み / 未設定 | مُعيَّن / غير مُعيَّن | Défini / Non défini | 已設定 / 未設定 | Задано / Не задано | Configurado / Sin configurar |
