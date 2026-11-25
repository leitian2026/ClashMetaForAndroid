# Backup inventory — CUSTOM #17
每项持久化数据默认备份。仅允许排除可重新下载（注明来源）、账号密码、备份文件自身。
| 键 | 持久化位置/来源 | 策略 |
|---|---|---|
| prefs:service | service SharedPreferences；排除 active_profile UUID | 备份 |
| prefs:custom_config_check | SharedPreferences custom_config_check | 备份 |
| prefs:custom_saved_ip_ui | SharedPreferences custom_saved_ip_ui | 备份 |
| prefs:custom_editor | SharedPreferences custom_editor | 备份 |
| prefs:custom_errors | SharedPreferences custom_errors | 备份 |
| prefs:app | SharedPreferences app | 备份 |
| prefs:tips | SharedPreferences tips | 备份 |
| room:imported | profiles Room Imported metadata; profile configs handled by legacy backup | 备份 |
| room:pending | profiles Room Pending metadata | 备份 |
| room:selections | profiles Room Selection | 备份 |
| dir:pending | filesDir/pending pending files | 备份 |
| dir:processing | processingDir | 备份 |
| file:custom/ip_history.json | filesDir/custom/ip_history.json | 备份 |
| dir:custom/update_guard | filesDir/custom/update_guard | 备份 |
| dir:custom/config_update_snapshot | filesDir/custom/config_update_snapshot | 备份 |
| dir:custom/crash | filesDir/custom/crash | 备份 |
| dir:custom/config_history | filesDir/custom/config_history incl. markers and pinned configs | 备份 |
| file:custom_network_identity.json | filesDir custom network identity | 备份 |
| file:custom_carrier_switch_*.json | filesDir per-profile carrier mappings | 备份 |
| dir:logs/clash-*.log | Clash logs | 备份 |
| file:backup/archive | generated backup archives | 不备份：备份文件自身 |
